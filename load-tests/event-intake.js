import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

const eventIntakeDuration = new Trend('event_intake_duration', true);
const eventIntakeFailed = new Rate('event_intake_failed');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'sendEvent',
      rate: 20,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 20,
      maxVUs: 20,
      gracefulStop: '5s',
    },
    measurement: {
      executor: 'constant-arrival-rate',
      exec: 'sendEvent',
      startTime: '12s',
      rate: 50,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 50,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    'checks{scenario:measurement}': ['rate==1'],
    'dropped_iterations{scenario:measurement}': ['count==0'],
    event_intake_failed: ['rate==0'],
  },
};

export function setup() {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const response = http.post(
    `${baseUrl}/api/v1/subscriptions`,
    JSON.stringify({
      name: 'load-test-receiver',
      endpointUrl: 'https://example.com/webhooks',
      eventTypes: ['load.test'],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'create_subscription',
      },
    },
  );

  if (response.status !== 201) {
    throw new Error(`subscription setup failed: status=${response.status} body=${response.body}`);
  }

  return {
    baseUrl,
    runId: `${Date.now()}`,
  };
}

export function sendEvent(data) {
  const idempotencyKey = [
    'load',
    data.runId,
    exec.scenario.name,
    exec.vu.idInTest,
    exec.scenario.iterationInTest,
  ].join('-');
  const response = http.post(
    `${data.baseUrl}/api/v1/events/load.test`,
    JSON.stringify({
      orderId: idempotencyKey,
      occurredAt: '2026-09-12T00:00:00Z',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: {
        name: 'accept_event',
      },
    },
  );

  let body = null;
  if (response.status === 202) {
    body = response.json();
  }
  const accepted = check(response, {
    'event is accepted': (result) => result.status === 202,
    'event is not replayed': (result) => result.headers['Idempotency-Replayed'] === 'false',
    'one delivery is created': () => body !== null && body.deliveryCount === 1 && body.replayed === false,
  });

  if (exec.scenario.name === 'measurement') {
    eventIntakeDuration.add(response.timings.duration);
    eventIntakeFailed.add(!accepted);
  }
}
