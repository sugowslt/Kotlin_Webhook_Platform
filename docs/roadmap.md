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
- [ ] 프로세스 종료 후 복구 시나리오

## 5단계: 검증과 설명

- [x] Testcontainers PostgreSQL 통합 테스트
- [x] WireMock 전달 실패 시나리오
- [x] 다중 Worker 중복 처리 검증
- [x] Micrometer 전달 지표
- [x] 고정 조건 부하 측정
- [x] Docker Compose 기본 전달 시연
- [x] Docker Compose 실패 후 수동 재전송 시연
- [x] Docker Compose 실행 경로와 시연 화면

Worker 선점 수와 결과별 처리 횟수·소요 시간을 Micrometer로 기록하고 Prometheus endpoint 노출까지 확인했습니다. 결과 태그는 여섯 값으로 제한했으며 전달 ID, 구독 ID, endpoint URL은 넣지 않았습니다.

이벤트 접수 경로는 k6로 초당 50건을 60초 동안 측정했습니다. 응답 시간 목표를 미리 정하지 않고 실제 측정값과 환경, 제외 범위를 함께 기록했습니다. Worker HTTP 전달 처리량은 별도 측정이 필요합니다.

Docker Compose로 애플리케이션, PostgreSQL, 로컬 Webhook 수신기, Prometheus, Grafana를 함께 실행합니다. 기본 전달과 `FAILED` 상태의 수동 재전송을 각각 스크립트로 재현할 수 있습니다.
