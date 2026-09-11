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
- [ ] 수동 재전송 API
- [ ] 프로세스 종료 후 복구 시나리오

## 5단계: 검증과 설명

- [x] Testcontainers PostgreSQL 통합 테스트
- [x] WireMock 전달 실패 시나리오
- [ ] 다중 Worker 중복 처리 검증
- [ ] Micrometer 전달 지표
- [ ] 고정 조건 부하 측정
- [ ] Docker Compose 실행 경로와 시연 화면

처리량과 응답시간은 실행 전 목표치로 확정하지 않습니다. 실제 측정값과 환경을 함께 기록합니다.
