# 테스트 실행 기록

## 2026-09-11

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1
- 명령: `gradlew.bat test`
- 결과: 13개 성공, 실패 0개, 오류 0개, skipped 0개
- 범위: HMAC-SHA256 서명, HTTP 재시도 판단과 backoff, Webhook URL 기본 검증
- 제외: PostgreSQL, Testcontainers, 실제 HTTP 전달, 다중 Worker

이번 결과는 전달 정책을 외부 인프라 없이 검증한 단위 테스트입니다. delivery queue와 Worker를 연결한 뒤 통합 테스트 결과를 별도로 추가합니다.
