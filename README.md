# HookRelay

Webhook 이벤트를 비동기로 전달하고 실패한 요청을 재시도하거나 격리하는 Kotlin/Spring Boot 프로젝트를 만들어봤습니다.

## 현재 상태

전달 정책을 먼저 고정한 뒤 이벤트 접수, 비동기 전달, 관측 환경까지 연결했습니다.

- HMAC-SHA256 서명과 검증
- HTTP 상태별 재시도 판단과 지수 backoff
- Webhook URL의 scheme·userinfo·사설 주소 검사
- 구독별 endpoint와 이벤트 유형 등록
- `Idempotency-Key` 기반 이벤트 중복 방지
- 이벤트와 전달 작업의 트랜잭션 저장
- Flyway 초기 schema
- `FOR UPDATE SKIP LOCKED`와 lease token 기반 작업 선점
- Worker 강제 종료 후 lease 만료 작업 재선점
- HMAC 서명 HTTP 전달과 시도 이력 저장
- `Retry-After`와 지수 backoff를 반영한 재시도
- `FAILED`·`DEAD_LETTER` 전달의 수동 재전송 API
- Bearer 토큰과 `OPERATOR` 권한을 사용한 수동 재전송 보호
- 전달 작업 선점 수와 결과별 처리 시간 지표
- k6로 고정 요청률 이벤트 접수 측정
- Docker Compose 기반 로컬 전달 시연과 Grafana 대시보드

Worker 실행 경로와 실패 분류는 단위 테스트로 확인했습니다. Flyway schema, 트랜잭션 rollback, 작업 선점 SQL과 lease 재선점은 Testcontainers PostgreSQL에서 검증했습니다. 두 Worker를 함께 실행한 테스트에서는 첫 Worker가 전달 중인 작업을 두 번째 Worker가 다시 선점하지 않는 것도 확인했습니다. Compose에서는 HTTP 응답 대기 중 Worker를 `SIGKILL`로 종료하고, 재기동한 Worker가 lease 만료 후 같은 전달 ID를 복구하는 과정을 확인했습니다. WireMock에서는 실제 HTTP 본문과 HMAC 헤더, `Retry-After`, redirect 차단을 확인했습니다. Micrometer 지표는 결과별 기록과 Prometheus scrape 응답까지 검증했습니다.

2026-09-13 로컬 Java 17과 Docker 29.7.2 환경에서 전체 테스트 52개가 통과했습니다. 실패 0개, 오류 0개, skipped 0개이며 PostgreSQL 17 Testcontainers 통합 테스트 14개와 WireMock HTTP 통합 테스트 3개가 포함됩니다. 인터넷 외부 주소로 요청을 보내지는 않았습니다.

## 동작 흐름

```mermaid
flowchart LR
    Publisher["이벤트 발행자"] -->|"POST /events"| API["Event API"]
    API -->|"이벤트와 전달 작업 저장"| DB[("PostgreSQL")]
    DB -->|"SKIP LOCKED로 작업 선점"| Worker["Delivery Worker"]
    Worker -->|"HMAC 서명 요청"| Target["Webhook 서버"]
    Target -->|"2xx"| Success["전달 완료"]
    Target -->|"408 · 429 · 5xx · timeout"| Retry["재시도 예약"]
    Target -->|"그 외 4xx"| Failed["영구 실패"]
    Retry --> DB
    Retry -->|"최대 횟수 초과"| DeadLetter["Dead Letter"]
    Failed --> Manual["수동 재전송"]
    DeadLetter --> Manual
    Manual --> DB
    Worker --> Metrics["전달 지표와 감사 기록"]
```

## 설계 기준

- 이벤트 접수는 빠르게 `202 Accepted`로 끝내고 전달은 Worker가 처리합니다.
- 같은 `Idempotency-Key`로 들어온 이벤트는 한 번만 저장합니다.
- Worker는 lease와 `FOR UPDATE SKIP LOCKED`를 사용해 전달 작업을 나눠 처리합니다.
- timeout, `408`, `429`, `5xx`는 재시도하고 나머지 `4xx`는 영구 실패로 분류합니다.
- 최대 시도 횟수를 넘긴 작업은 Dead Letter 상태로 옮깁니다.
- `FAILED`와 `DEAD_LETTER`만 수동 재전송할 수 있습니다. 기존 전달 ID·시도 횟수·이력은 유지하고 대기열에 다시 넣습니다.
- 수동 재전송은 32자 이상의 운영자 Bearer 토큰이 있어야 요청할 수 있습니다.
- 전달 보장은 at-least-once입니다. 외부 서버가 요청을 처리한 직후 Worker가 종료되면 같은 전달 ID가 다시 전송될 수 있습니다.
- Webhook 요청은 HMAC-SHA256으로 서명합니다.
- 사용자가 등록한 URL을 서버가 호출하므로 SSRF 방어를 별도 경계로 둡니다.

## 기술 구성

- Kotlin 2.2.21, Java 17
- Spring Boot 4.0.3, Gradle
- PostgreSQL, Flyway
- Spring MVC, Spring Security, JPA
- Java HttpClient
- Micrometer, Prometheus scrape endpoint
- Docker Compose, Prometheus, Grafana
- Testcontainers
- WireMock
- k6

Redis와 Kafka는 첫 구현에 넣지 않습니다. PostgreSQL만으로 작업 선점·재시도·복구 계약을 검증한 뒤 병목이 확인될 때 도입 여부를 판단합니다.

## 운영 지표

- `hookrelay.delivery.claimed`: Worker가 선점한 전달 작업 수
- `hookrelay.delivery.processing`: 결과별 처리 횟수와 소요 시간

처리 결과는 `succeeded`, `retry_scheduled`, `failed`, `dead_letter`, `lease_lost`, `processing_error`로 구분합니다. 전달 ID, 구독 ID, endpoint URL처럼 계속 늘어날 수 있는 값은 태그에서 제외했습니다.

지표는 `/actuator/metrics`에서 확인할 수 있고 Prometheus scrape 형식은 `/actuator/prometheus`에서 제공합니다. 로컬 Docker Compose 환경에서는 Prometheus가 5초마다 수집하며 Grafana의 `Hook Relay Overview` 대시보드에서 상태와 전달 결과를 확인할 수 있습니다.

## 로컬 시연

Docker Desktop을 실행한 뒤 PowerShell에서 아래 명령을 사용합니다.

```powershell
.\demo\run-demo.ps1
```

스크립트는 애플리케이션·PostgreSQL·Webhook 수신기·Prometheus·Grafana를 기동하고 구독 등록부터 실제 전달 성공까지 확인합니다. 완료 후 Grafana는 [http://127.0.0.1:3000/d/hook-relay-overview](http://127.0.0.1:3000/d/hook-relay-overview)에서 볼 수 있습니다.

실패한 전달의 수동 재전송은 별도 스크립트로 확인합니다. 첫 요청을 `400`으로 실패시킨 뒤 재전송 API를 호출하고, 두 번째 요청이 `204`로 끝나는지와 시도 이력 `FAILED,SUCCEEDED`를 검사합니다.

```powershell
.\demo\run-redelivery-demo.ps1
```

스크립트의 기본 운영자 토큰은 Compose 전용 공개 시연값입니다. 다른 값을 시험하려면 Compose의 `HOOK_RELAY_OPERATOR_TOKEN`과 스크립트의 `-OperatorToken` 인자를 같은 32자 이상 값으로 바꿉니다.

Worker 강제 종료 후 복구는 아래 스크립트로 확인합니다. 첫 요청을 수신기가 보류한 상태에서 애플리케이션만 `SIGKILL`로 종료하고, 재기동한 Worker가 lease 만료 후 같은 전달 ID를 다시 보내는지 검사합니다.

```powershell
.\demo\run-crash-recovery-demo.ps1
```

```powershell
docker compose down
```

로컬 시연 환경에서만 HTTP와 사설 Webhook 대상을 명시적으로 허용합니다. 기본 애플리케이션 설정은 HTTPS와 공개 주소만 허용합니다. 세부 실행 방법과 데이터 초기화는 [Docker Compose 로컬 시연](docs/demo.md)에 정리했습니다.

## API 예시

구독을 등록하면 Webhook 요청 검증에 사용할 서명 비밀값이 응답에 포함됩니다.

```bash
curl -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order receiver",
    "endpointUrl": "https://hooks.example.com/events",
    "eventTypes": ["order.created", "order.cancelled"]
  }'
```

이벤트 접수 API는 JSON 본문과 멱등키를 받아 대상 구독 수만큼 전달 작업을 만듭니다.

```bash
curl -X POST http://localhost:8080/api/v1/events/order.created \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-20260911-1" \
  -d '{"orderId": 1001}'
```

같은 멱등키와 같은 요청을 다시 보내면 기존 이벤트 ID를 반환하고 `Idempotency-Replayed: true` 헤더를 붙입니다. 같은 키로 다른 이벤트 유형이나 본문을 보내면 `409 Conflict`로 처리합니다.

실패가 확정된 전달은 전달 ID로 다시 요청할 수 있습니다. 접수된 작업은 `202 Accepted`와 `PENDING` 상태를 반환하고 Worker가 비동기로 처리합니다. 이미 대기·처리 중이거나 성공한 전달은 `409 Conflict`, 없는 전달은 `404 Not Found`로 응답합니다.

```bash
curl -X POST http://localhost:8080/api/v1/deliveries/{deliveryId}/redeliveries \
  -H "Authorization: Bearer $HOOK_RELAY_OPERATOR_TOKEN"
```

토큰 누락, 잘못된 형식, 불일치는 모두 `401 Unauthorized`로 응답합니다. 기본 설정에는 토큰값이 없으며 `HOOK_RELAY_OPERATOR_TOKEN`이 32자보다 짧으면 애플리케이션이 시작되지 않습니다. Compose에 적힌 `local-demo-operator-token-not-secret`은 loopback 시연 전용이므로 다른 환경에서 사용하지 않습니다.

## 검증 현황

- [x] 같은 멱등키를 두 번 보내도 전달 작업이 중복 생성되지 않는가
- [x] Worker 두 개가 같은 작업을 동시에 처리하지 않는가
- [x] timeout과 재시도 가능한 HTTP 상태를 정책대로 분류하는가
- [x] 실제 Worker 프로세스를 종료해도 lease 만료 후 작업을 복구하는가
- [x] payload가 바뀌면 HMAC 검증이 실패하는가
- [x] localhost와 사설 주소가 Webhook 대상으로 등록되지 않는가
- [x] 최대 시도 횟수를 넘긴 작업이 Dead Letter 상태로 이동하는가
- [x] 실패한 전달만 수동 재전송되고 동시 요청은 한 건만 접수되는가
- [x] 운영자 토큰이 없거나 일치하지 않으면 수동 재전송이 거부되는가

2026-09-12 로컬 환경에서 이벤트 접수 경로에 초당 50건을 60초 동안 보냈습니다. 측정 요청 3,001건의 p50은 13.77ms, p95는 28.20ms, p99는 49.73ms였으며 오류와 dropped iteration은 없었습니다. 이벤트마다 전달 작업 1건을 저장했고 DB 건수도 요청 수와 일치했습니다. Worker HTTP 전달은 이번 측정에서 제외했습니다.

## 문서

- [PostgreSQL 작업 큐를 먼저 사용하는 이유](docs/adr/0001-postgresql-delivery-queue.md)
- [Webhook 전달을 at-least-once로 복구하는 이유](docs/adr/0002-at-least-once-delivery.md)
- [수동 재전송 API를 운영자 Bearer 토큰으로 보호하는 이유](docs/adr/0003-operator-bearer-token.md)
- [구현 순서와 완료 기준](docs/roadmap.md)
- [테스트 실행 기록](docs/test-execution-log.md)
- [이벤트 접수 부하 측정](docs/load-test.md)
- [Docker Compose 로컬 시연](docs/demo.md)

## 참고 기준

- [GitHub Webhook 권장사항](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
- [GitHub Webhook 재전송](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/redelivering-webhooks)
- [GitHub REST Webhook delivery API](https://docs.github.com/en/rest/repos/webhooks)
- [Stripe Webhook 재시도](https://docs.stripe.com/webhooks)
- [OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
- [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Micrometer metric naming](https://docs.micrometer.io/micrometer/reference/concepts/naming.html)
- [k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)
- [Docker Compose 시작 순서](https://docs.docker.com/compose/how-tos/startup-order/)
- [Docker Compose 강제 종료](https://docs.docker.com/reference/cli/docker/compose/kill/)
- [PostgreSQL `SKIP LOCKED`](https://www.postgresql.org/docs/17/sql-select.html)
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)
- [Spring Boot Testcontainers 지원](https://docs.spring.io/spring-boot/reference/features/dev-services.html)
- [Spring Security 요청 권한 설정](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [Spring Security Stateless 인증](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
