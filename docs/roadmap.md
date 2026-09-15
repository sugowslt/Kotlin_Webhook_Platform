# 구현 순서와 완료 기준

## 1단계: 전달 정책

- [x] HMAC-SHA256 서명과 검증
- [x] HTTP 상태별 재시도 정책
- [x] 지수 backoff와 jitter 계산
- [x] Webhook URL 기본 검증과 사설 주소 차단
- [x] 단위 테스트 실행 결과 기록

## 2단계: 이벤트 접수

- [x] 구독 등록 API
- [x] 이벤트 접수 API와 `Idempotency-Key`
- [x] Flyway 초기 schema
- [x] 이벤트와 전달 작업의 원자적 저장

PostgreSQL 통합 테스트에서 고유 제약, 멱등 재요청과 충돌, 전달 작업 실패 시 트랜잭션 rollback을 확인했습니다.

## 3단계: 전달 Worker

- [x] `FOR UPDATE SKIP LOCKED` 작업 선점
- [x] lease 만료와 재선점
- [x] HMAC 서명 HTTP 요청
- [x] 전달 시도 이력

Worker 정책과 HTTP 요청 생성을 단위 테스트로 확인했습니다. PostgreSQL 통합 테스트에서는 잠긴 행을 기다리지 않고 건너뛰는 동작과 lease token을 이용한 재선점·늦은 결과 거부를 검증했습니다.

## 4단계: 실패 복구

- [x] timeout·`408`·`429`·`5xx` 재시도
- [x] 최대 횟수 초과 시 Dead Letter 전환
- [x] `FAILED`·`DEAD_LETTER` 수동 재전송 API
- [x] 동시 재전송 요청의 단일 상태 전이
- [x] 프로세스 종료 후 lease 만료 복구 시나리오
- [x] 수동 재전송 API 운영자 인증과 권한

## 5단계: 검증과 설명

- [x] Testcontainers PostgreSQL 통합 테스트
- [x] WireMock 전달 실패 시나리오
- [x] 다중 Worker 중복 처리 검증
- [x] Micrometer 전달 지표
- [x] 작업 대기열 깊이와 선점 SQL 실행 시간 지표
- [x] 고정 지연 조건의 작업 대기열 적체 관측
- [x] Prometheus 경보 규칙과 단위 테스트
- [x] 고정 조건 부하 측정
- [x] Worker HTTP 전달 처리량 반복 측정
- [x] 대기열 크기와 Worker 수에 따른 확장 특성 측정
- [x] Docker Compose 기본 전달 시연
- [x] Docker Compose 실패 후 수동 재전송 시연
- [x] Docker Compose Worker 강제 종료 복구 시연
- [x] Docker Compose 실행 경로와 시연 화면

Worker 선점 수와 결과별 처리 횟수·소요 시간을 Micrometer로 기록하고 Prometheus endpoint 노출까지 확인했습니다. 작업 대기열은 네 상태의 Gauge로 기록하고 선점 SQL 실행 시간은 결과별 Timer로 측정합니다. 전달 ID, 구독 ID, endpoint URL은 태그에 넣지 않았습니다.

Worker와 Gauge sampler가 서로 기다리지 않도록 Spring 스케줄러 pool을 2개 스레드로 분리했습니다. 응답을 250ms 늦춘 수신기로 전달 100건을 처리하면서 PostgreSQL과 Prometheus의 적체 변화를 함께 확인했습니다. Prometheus 경보는 전체 대상 중단, lease 없는 처리 작업, 선점 실패 세 조건만 두고 `promtool`로 검증했습니다.

이벤트 접수 경로는 k6로 초당 50건을 60초 동안 측정했습니다. Worker HTTP 전달은 고유 작업 500건을 미리 적재하고 기본 batch 20건·fixed delay 1초 조건에서 3회 측정했습니다. 이어서 대기열 500건·1,000건과 Worker 1개·2개·4개 조합을 각각 3회 비교했습니다. Worker 4개는 1개 대비 3.46~3.73배 처리했으며 현재 범위에서는 PostgreSQL 선점 실패나 중복 처리가 없었습니다. 각 결과에는 환경, 산정 기준, 제외 범위를 함께 기록했습니다.

Docker Compose로 애플리케이션, PostgreSQL, 로컬 Webhook 수신기, Prometheus, Grafana를 함께 실행합니다. 기본 전달과 `FAILED` 상태의 수동 재전송을 각각 스크립트로 재현할 수 있습니다.
