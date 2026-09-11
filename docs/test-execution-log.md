# 테스트 실행 기록

## 2026-09-11 전달 정책

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1
- 명령: `gradlew.bat test`
- 결과: 13개 성공, 실패 0개, 오류 0개, skipped 0개
- 범위: HMAC-SHA256 서명, HTTP 재시도 판단과 backoff, Webhook URL 기본 검증
- 제외: PostgreSQL, Testcontainers, 실제 HTTP 전달, 다중 Worker

이번 결과는 전달 정책을 외부 인프라 없이 검증한 단위 테스트입니다. delivery queue와 Worker를 연결한 뒤 통합 테스트 결과를 별도로 추가합니다.

## 2026-09-11 이벤트 접수

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1
- 명령: `gradlew.bat test`
- 결과: 20개 성공, 실패 0개, 오류 0개, skipped 0개
- 범위: 기존 전달 정책, 구독 등록, 이벤트 유형 검증, 이벤트 접수와 멱등 재요청·충돌 처리
- 제외: PostgreSQL, Testcontainers, 실제 HTTP 전달, 다중 Worker

멱등키 중복 처리는 단위 테스트에서 서비스 계약만 확인했습니다. `ON CONFLICT`와 트랜잭션 rollback은 PostgreSQL 통합 테스트를 실행한 뒤 결과를 추가합니다.
