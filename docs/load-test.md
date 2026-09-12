# 이벤트 접수 부하 측정

## 측정 범위

HTTP 이벤트 접수부터 JSON 검증, 멱등 저장, 구독 조회, 전달 작업 저장까지 측정했습니다. 구독은 1개만 등록해 이벤트마다 전달 작업이 1개 생성되도록 고정했습니다.

Worker 실행 주기는 1시간으로 늘렸습니다. 측정 중 모든 전달 작업은 `PENDING` 상태를 유지했고 Webhook HTTP 요청은 발생하지 않았습니다. 따라서 아래 결과는 이벤트 접수 경로의 로컬 기준값이며 Worker 전달 처리량은 나타내지 않습니다.

## 실행 조건

| 항목 | 조건 |
| --- | --- |
| 측정 일시 | 2026-09-12 |
| 호스트 | Intel Core Ultra 7 258V, 8코어·8스레드, RAM 31.51GiB |
| 애플리케이션 | Java 17.0.18, Spring Boot 4.0.3, 기본 JVM·HikariCP·Tomcat 설정 |
| 데이터베이스 | PostgreSQL 17.11, 새 데이터베이스 |
| Docker 할당 | CPU 8개, 메모리 15.38GiB |
| 부하 도구 | `grafana/k6:2.1.0` 로컬 Docker 실행 |
| 준비 구간 | 초당 20건, 10초, 200건 |
| 측정 구간 | 초당 50건, 60초, 3,001건 |
| VU | 준비 20개, 측정 50개 사전 할당 |
| 요청 | 고유 `Idempotency-Key`, 약 100바이트 JSON, 활성 구독 1개 |
| 로그 | 요청별 INFO 로그 활성화 |

k6의 `constant-arrival-rate`를 사용해 응답 속도와 무관하게 정해진 요청률을 시도했습니다. 응답 시간 목표는 측정 전에 정하지 않았고 기능 계약과 요청 누락 여부만 통과 조건으로 뒀습니다.

## 결과

| 지표 | 결과 |
| --- | ---: |
| 측정 요청 | 3,001건 |
| 요청률 | 초당 50건 |
| 평균 | 15.47ms |
| p50 | 13.77ms |
| p90 | 22.34ms |
| p95 | 28.20ms |
| p99 | 49.73ms |
| 최대 | 111.67ms |
| 기능 검사 | 9,003개 성공, 실패 0개 |
| 오류율 | 0% |
| dropped iteration | 0건 |

준비 구간을 포함한 이벤트 3,201건은 PostgreSQL의 `webhook_events` 3,201건, `webhook_deliveries` 3,201건과 일치했습니다. 서로 다른 멱등키도 3,201개였으며 전달 작업은 모두 `PENDING`, 전달 시도 이력은 0건이었습니다.

이 결과는 한 대의 로컬 장비에서 한 번 실행한 기준값입니다. 운영 환경의 용량이나 최대 처리량으로 해석할 수 없고, Worker의 HTTP 전달과 재시도 비용도 포함하지 않습니다.

## 재현 방법

새 PostgreSQL 컨테이너를 실행합니다.

```powershell
docker run --name hook-relay-load-postgres `
  -e POSTGRES_DB=hook_relay_load `
  -e POSTGRES_USER=hook_relay `
  -e POSTGRES_PASSWORD=hook_relay `
  -p 127.0.0.1:55432:5432 `
  -d postgres:17-alpine
```

애플리케이션은 Worker의 다음 실행이 측정 구간 이후가 되도록 시작합니다.

```powershell
.\gradlew.bat bootRun --args="--server.port=18080 --spring.datasource.url=jdbc:postgresql://localhost:55432/hook_relay_load --spring.datasource.username=hook_relay --spring.datasource.password=hook_relay --hook-relay.worker.poll-interval-millis=3600000"
```

애플리케이션이 시작되면 다른 PowerShell에서 k6를 실행합니다. k6의 `setup` 함수가 구독 1개를 등록합니다.

```powershell
docker run --rm `
  --mount "type=bind,source=${PWD}\load-tests,target=/scripts,readonly" `
  -e BASE_URL=http://host.docker.internal:18080 `
  grafana/k6:2.1.0 run /scripts/event-intake.js
```

측정 후 애플리케이션을 종료하고 전용 컨테이너를 제거합니다.

```powershell
docker stop hook-relay-load-postgres
docker rm hook-relay-load-postgres
```

같은 데이터베이스로 다시 실행하면 활성 구독이 늘어나 이벤트당 전달 작업 수가 달라집니다. 측정마다 새 컨테이너를 사용해야 같은 조건을 유지할 수 있습니다.
