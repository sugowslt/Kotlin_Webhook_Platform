import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const eventCount = Number.parseInt(__ENV.EVENT_COUNT || '500', 10);
const virtualUsers = Number.parseInt(__ENV.VUS || '50', 10);

if (!Number.isInteger(eventCount) || eventCount <= 0) {
  throw new Error('EVENT_COUNT must be a positive integer');
}
if (!Number.isInteger(virtualUsers) || virtualUsers <= 0) {
  throw new Error('VUS must be a positive integer');
}
if (!__ENV.BASE_URL || !__ENV.EVENT_TYPE || !__ENV.RUN_ID) {
  throw new Error('BASE_URL, EVENT_TYPE, and RUN_ID are required');
}

export const options = {
  scenarios: {
    seed: {
      executor: 'shared-iterations',
      vus: virtualUsers,
      iterations: eventCount,
      maxDuration: '2m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const idempotencyKey = `worker-${__ENV.RUN_ID}-${sequence}`;
  const response = http.post(
    `${__ENV.BASE_URL}/api/v1/events/${__ENV.EVENT_TYPE}`,
    JSON.stringify({
      orderId: idempotencyKey,
      sequence,
      occurredAt: new Date().toISOString(),
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: {
        name: 'seed_worker_delivery',
      },
    },
  );

  let body = null;
  if (response.status === 202) {
    body = response.json();
  }
  check(response, {
    'event is accepted': (result) => result.status === 202,
    'event is not replayed': (result) => result.headers['Idempotency-Replayed'] === 'false',
    'one delivery is created': () => body !== null && body.deliveryCount === 1 && body.replayed === false,
  });
}
