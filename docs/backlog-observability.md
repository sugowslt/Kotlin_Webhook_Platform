# 작업 대기열 적체 관측

## 측정 범위

Worker가 처리할 작업보다 이벤트를 빠르게 접수해 작업 대기열을 만들고 PostgreSQL 상태와 Prometheus Gauge가 같은 변화를 보여주는지 확인했습니다. 처리량 한계를 찾는 부하 테스트가 아니라 관측 경로의 동작을 확인하는 시연입니다.

## 실행 조건

| 항목 | 조건 |
| --- | --- |
| 측정 일시 | 2026-09-13 |
| 환경 | Windows, Java 17.0.18, Docker 29.7.2, Docker Compose 5.5.1 |
| 애플리케이션 | Spring Boot 4.0.3, 단일 Worker |
| 이벤트 | 고유 이벤트 유형 100건, 이벤트당 전달 작업 1건 |
| 수신기 | 로컬 Python 수신기, 응답마다 250ms 지연 후 `204` |
| Worker | batch 20건, poll 1초, lease 30초 |
| Gauge | 5초마다 PostgreSQL 상태 갱신 |
| 스케줄러 | Worker와 Gauge sampler가 공유하는 pool 2개 스레드 |

기존 Docker volume은 유지했습니다. DB 결과는 실행마다 새로 만든 고유 이벤트 유형으로 구분했고 측정이 끝난 뒤 다른 활성 전달 작업이 없는지 확인했습니다.

## 결과

| 지표 | 결과 |
| --- | ---: |
| 이벤트 접수 시간 | 1.86초 |
| 전체 배수 시간 | 34.08초 |
| PostgreSQL `claimable` 최대 | 80건 |
| PostgreSQL `leased` 최대 | 20건 |
| Prometheus `claimable` 최대 | 80건 |
| Prometheus `leased` 최대 | 17건 |
| 측정 구간 선점 쿼리 | 8회 |
| 선점 쿼리 평균 | 12.685ms |
| 최종 이벤트·전달·시도 이력 | 100건·100건·100건 |
| 실패·정체 작업 | 0건 |

PostgreSQL은 1초 간격, Gauge는 5초 간격으로 표본화하므로 `leased` 최대값은 서로 다릅니다. 두 경로에서 적체와 처리 중 상태가 모두 0보다 크게 관측됐고 실행 종료 후 네 Gauge는 모두 0으로 돌아왔습니다.

선점 쿼리 평균은 이벤트 접수 시작부터 대기열이 비워질 때까지 실행된 8회의 Timer 차이로 계산했습니다. 단일 쿼리의 최대 시간이나 작업 한 건당 DB 비용을 뜻하지 않습니다.

## 발견과 수정

첫 실행에서는 PostgreSQL이 `leased` 최대 20건을 기록했지만 Prometheus 값은 0이었습니다. 전달 결과 100건과 시도 이력 100건은 모두 일치해 전달 실패는 아니었습니다.

Worker와 Gauge sampler가 기본 단일 스케줄러 스레드를 공유한 것이 원인이었습니다. Worker가 선점한 20건을 순차 처리하는 동안 sampler가 실행되지 못했고, batch가 끝난 뒤에야 Gauge가 갱신됐습니다. `spring.task.scheduling.pool.size`를 2로 설정해 두 작업이 독립적으로 실행되도록 바꿨습니다. 같은 조건으로 다시 실행한 결과 Prometheus에서도 `leased` 최대 17건을 확인했습니다.

## 경보 기준

로컬 한 번의 측정값만으로 `claimable > 80` 같은 운영 임계값을 정하지 않았습니다. 실제 경보값은 처리 지연 SLO, 운영 유입량, Worker 수를 정한 뒤 다시 계산해야 합니다.

현재 규칙은 원인과 조치가 분명한 세 조건만 포함합니다.

- 모든 `hook-relay` 수집 대상 중단
- lease 없는 `PROCESSING` 작업이 1분 이상 유지됨
- 최근 5분 선점 실패가 1분 이상 계속 관측됨

세 규칙은 `promtool check rules`로 문법을 검사했고 정상·대상 중단·정체·선점 실패 4개 시나리오를 `promtool test rules`로 확인했습니다. Alertmanager는 연결하지 않았으므로 로컬에서는 Prometheus 경보 상태만 확인합니다.

## 재현 방법

Docker Desktop을 실행한 뒤 저장소 루트에서 측정 스크립트를 실행합니다.

```powershell
.\demo\run-backlog-observability-demo.ps1
```

이벤트 수는 40건부터 500건까지 바꿀 수 있습니다. 기본 250ms 지연과 30초 lease를 함께 고려하면 한 batch 처리 시간이 lease를 넘지 않는 범위에서 사용해야 합니다.

```powershell
.\demo\run-backlog-observability-demo.ps1 -EventCount 120
```

경보 규칙은 Prometheus 이미지에 포함된 `promtool`로 검사합니다.

```powershell
docker compose run --rm --no-deps --entrypoint promtool prometheus check rules /etc/prometheus/rules/hook-relay-alerts.yml
docker compose run --rm --no-deps --entrypoint promtool prometheus test rules /etc/prometheus/tests/hook-relay-alerts.test.yml
```
