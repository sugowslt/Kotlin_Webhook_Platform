# ADR 0002: Webhook 전달은 at-least-once로 복구합니다

- 상태: 채택
- 작성일: 2026-09-12

## 배경

Worker는 PostgreSQL에서 전달 작업을 선점한 뒤 외부 Webhook 서버에 HTTP 요청을 보냅니다. PostgreSQL 상태 변경과 외부 서버의 처리는 하나의 트랜잭션으로 묶을 수 없습니다. 외부 서버가 요청을 처리한 직후 Worker가 결과를 기록하지 못하고 종료되면 전달 성공 여부를 DB만으로 확정할 수 없습니다.

미확정 작업을 버리면 전달이 누락될 수 있습니다. 다시 보내면 외부 서버가 이미 처리한 요청과 겹칠 수 있습니다. 프로세스 종료 상황에서는 두 위험을 동시에 없앨 수 없으므로 전달 계약을 정해야 합니다.

## 선택

HookRelay는 미확정 작업을 다시 보내는 at-least-once 방식을 사용합니다.

- Worker는 작업을 `PROCESSING`으로 바꾸고 lease token과 만료 시각을 저장합니다.
- 결과 기록은 현재 lease token이 일치할 때만 허용합니다.
- Worker가 종료돼 lease가 만료되면 다른 Worker가 같은 작업을 재선점합니다.
- 재시도와 복구 과정에서도 `X-HookRelay-Delivery` 값은 같은 전달 ID로 유지합니다.
- 전달 대상은 전달 ID를 멱등키로 저장해 중복 처리를 막아야 합니다.

## 확인 결과

로컬 Compose에서 Webhook 응답을 보류한 뒤 애플리케이션을 `SIGKILL`로 종료했습니다. 종료 직후 전달은 `PROCESSING`, 시도 횟수 0회, 완료 이력 0건으로 남았습니다. 애플리케이션을 다시 시작하자 Worker는 lease 만료 이후 같은 전달 ID를 재선점했고 최종 상태는 `SUCCEEDED`가 됐습니다.

수신기에는 같은 전달 ID가 두 번 기록됐습니다. 첫 요청은 수신 후 응답을 보류했고 두 번째 요청은 `204`로 끝났습니다. DB의 시도 이력에는 결과 기록까지 끝난 두 번째 요청만 `SUCCEEDED`로 남았습니다.

## 영향

- Worker가 결과를 기록하지 못한 전달도 lease 만료 후 다시 처리할 수 있습니다.
- 외부 서버에는 같은 전달이 두 번 이상 도착할 수 있습니다.
- 전달 시도 이력은 Worker가 결과를 기록한 요청만 포함합니다. 수신 여부가 불확실한 중단 요청까지 완료 이력으로 만들지 않습니다.
- exactly-once로 표현하지 않으며 전달 대상의 멱등 처리를 API 계약에 포함합니다.

## 참고

- [GitHub Webhook 권장사항](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
- [Docker Compose `kill`](https://docs.docker.com/reference/cli/docker/compose/kill/)
- [PostgreSQL `SKIP LOCKED`](https://www.postgresql.org/docs/17/sql-select.html)
