# Worker HTTP 전달 처리량 측정

## 측정 범위

이벤트 접수가 끝난 전달 작업을 Worker가 선점하고 로컬 Webhook 수신기에 HTTP 요청을 보낸 뒤, 성공 결과와 시도 이력을 PostgreSQL에 기록하는 구간을 측정했습니다.

이벤트 접수 시간은 처리량 계산에서 제외했습니다. Worker 실행 주기를 1시간으로 늘린 상태에서 매회 이벤트 500건을 먼저 적재하고, 모든 전달 작업이 `PENDING`이며 시도 이력이 0건인지 확인한 뒤 Worker를 기본 주기로 다시 시작했습니다.

## 실행 조건

| 항목 | 조건 |
| --- | --- |
| 측정 일시 | 2026-09-15 |
| 호스트 | Windows 11 10.0 amd64 |
| 애플리케이션 | Java 17.0.18, Gradle 9.3.1, Spring Boot 4.0.3 |
| Docker | Engine 29.7.2, Compose 5.5.1, CPU 8개, 메모리 15.38GiB |
| 데이터베이스 | PostgreSQL 17.11, 기존 volume 유지 |
| 부하 도구 | `grafana/k6:2.1.0` 로컬 Docker 실행 |
| 적재 조건 | `shared-iterations`, VU 50개, 매회 고유 이벤트 500건 |
| 구독 | 매회 고유 이벤트 유형을 받는 구독 1개 |
| Worker | 단일 인스턴스, batch 20건, fixed delay 1초, lease 30초 |
| 수신기 | 로컬 Python 수신기, 응답 지연 0ms, `204 No Content` |
| 반복 | 같은 조건으로 3회 |

k6의 [`shared-iterations`](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/shared-iterations/)를 사용해 VU가 정확히 500건을 나눠 요청하도록 했습니다. HTTP `202`, 멱등 재사용 없음, 이벤트당 전달 작업 1건을 검사하고 [`thresholds`](https://grafana.com/docs/k6/latest/using-k6/thresholds/)로 검사 실패·HTTP 오류·누락된 iteration이 있으면 실행도 실패하게 했습니다.

Worker의 1초 주기는 Spring `fixedDelay`입니다. 이전 실행이 끝난 시점부터 다음 실행까지 지연되므로 batch 내부 처리 시간과 batch 사이 대기 시간이 전체 처리 구간에 함께 반영됩니다. 자세한 기준은 [Spring Task 실행과 스케줄링](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)에 맞췄습니다.

## 산정 방법

처리 구간은 해당 회차의 첫 번째 전달 시도 `started_at`부터 마지막 전달 시도 `finished_at`까지입니다. 처리량은 `500 / 처리 구간(초)`로 계산했습니다.

스크립트가 기록하는 전체 경과 시간에는 애플리케이션 컨테이너 재생성과 health check 대기가 포함되므로 처리량 계산에는 사용하지 않았습니다. 전달 1건 평균은 애플리케이션의 `hookrelay_delivery_processing_seconds` Timer로 확인했습니다. 이 Timer에는 URL 검사, HTTP 요청, 결과 저장이 포함됩니다.

## 결과

| 회차 | 적재 시간 | 전체 경과 | 처리 구간 | 처리량 | 전달 1건 평균 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2.56초 | 37.00초 | 28.006초 | 17.85건/초 | 7.045ms |
| 2 | 2.51초 | 37.47초 | 27.967초 | 17.88건/초 | 7.010ms |
| 3 | 2.59초 | 37.85초 | 28.104초 | 17.79건/초 | 7.282ms |
| 평균 | 2.55초 | 37.44초 | 28.026초 | 17.84건/초 | 7.112ms |

- 처리량 중앙값: 17.85건/초
- 처리량 범위: 17.79~17.88건/초
- 회차별 k6 기능 검사: 1,500개 성공, 실패 0개
- 회차별 이벤트·전달·시도 이력·수신 요청: 각각 500건
- HTTP 오류와 dropped iteration: 0건
- 최종 실패 작업과 활성 전달 작업: 0건

전달 1건의 처리 시간은 평균 약 7.1ms였지만 Worker는 20건을 처리한 뒤 1초를 기다립니다. 따라서 이번 조건의 유효 처리량은 평균 17.84건/초로 나타났습니다. 이 값은 현재 기본 설정을 설명하는 로컬 기준값이며 시스템의 최대 처리량은 아닙니다.

## 재현 방법

Docker Desktop을 실행한 뒤 저장소 루트에서 아래 명령을 실행합니다.

```powershell
.\demo\run-worker-throughput-demo.ps1
```

건수, 적재 VU, 반복 횟수는 인자로 바꿀 수 있습니다.

```powershell
.\demo\run-worker-throughput-demo.ps1 -EventCount 1000 -SeedVus 80 -Repetitions 3
```

스크립트는 활성 전달 작업이 남아 있으면 시작하지 않습니다. 실행마다 고유 이벤트 유형과 구독을 사용하며 기존 데이터를 삭제하지 않습니다. 측정이 끝나거나 중간에 실패해도 Worker 주기 1초와 수신기 지연 250ms의 Compose 기본값으로 복구를 시도합니다.

## 해석 범위

이번 결과에는 단일 애플리케이션·단일 Worker, 로컬 네트워크, 응답 지연 0ms, 성공 응답만 포함됩니다. 다중 Worker, TLS, 외부 네트워크 지연, 재시도, timeout, DB 크기별 변화는 측정하지 않았습니다. 운영 용량이나 SLO를 정하려면 실제 배포 환경과 예상 지연 분포로 다시 측정해야 합니다.
