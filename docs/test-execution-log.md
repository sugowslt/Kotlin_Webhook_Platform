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

## 2026-09-11 전달 Worker

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1
- 명령: `gradlew.bat test`, `gradlew.bat check`
- 결과: 28개 성공, 실패 0개, 오류 0개, skipped 0개
- 범위: 기존 20개 단위 테스트, HMAC 요청 생성, HTTP 상태별 결과 처리, 네트워크 실패와 Dead Letter, `Retry-After` 해석, 배치 내 오류 격리
- 제외: PostgreSQL 작업 선점 SQL, lease 만료 후 재선점, 실제 HTTP 서버, 다중 Worker

이 실행 시점에는 작업 선점 SQL과 시도 이력 저장을 코드와 migration으로만 확인했습니다. 실제 DB 결과는 아래 PostgreSQL 통합 테스트에 따로 기록했습니다.

## 2026-09-11 PostgreSQL 통합 테스트

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2
- 컨테이너: PostgreSQL 17 Alpine, Testcontainers 2.0.5
- 최종 명령: `gradlew.bat test --rerun-tasks`
- 최종 결과: 33개 성공, 실패 0개, 오류 0개, skipped 0개
- 통합 테스트: 5개 성공
- 범위: Flyway migration, 멱등 저장과 충돌, 트랜잭션 rollback, `FOR UPDATE SKIP LOCKED`, lease 만료 후 재선점, 이전 lease token 결과 거부, 시도 이력 저장
- 제외: 실제 HTTP 서버, 다중 Worker 프로세스, 부하 측정

첫 실행은 `com.fasterxml.jackson.databind.ObjectMapper` bean이 없어 애플리케이션 컨텍스트 초기화 단계에서 5개가 실패했습니다. Spring Boot 4의 기본 JSON 구성에 기대지 않고 `com.fasterxml` ObjectMapper를 명시적으로 등록했습니다. 기존 코드와 테스트의 Jackson 패키지를 바꾸지 않으면서 런타임 의존성을 분명히 할 수 있어 이 방식을 선택했습니다.

두 번째 실행에서는 3개가 성공하고 lease 관련 2개가 실패했습니다. 이벤트의 `next_attempt_at`은 애플리케이션 현재 시각으로 저장됐지만 테스트는 그보다 이른 고정 시각으로 선점을 시도한 것이 원인이었습니다. 임의 대기를 추가하지 않고 DB에 저장된 `next_attempt_at`을 읽어 lease 검증의 기준 시각으로 사용했습니다. 수정 후 통합 테스트 5개와 전체 테스트 33개가 모두 통과했습니다.

## 2026-09-11 실제 HTTP 전달 테스트

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1
- 테스트 서버: WireMock 3.13.2 standalone, 동적 로컬 포트
- 최종 명령: `gradlew.bat test --rerun-tasks`
- 최종 결과: 36개 성공, 실패 0개, 오류 0개, skipped 0개
- HTTP 통합 테스트: 3개 성공
- 범위: JSON 본문, HMAC 서명과 timestamp·delivery ID 헤더, 503 `Retry-After`, redirect 차단
- 제외: 인터넷 외부 주소, 다중 Worker 프로세스, 부하 측정

첫 실행에서는 WireMock 서버가 Jetty 11 구현을 찾지 못해 HTTP 통합 테스트 3개가 서버 시작 단계에서 실패했습니다. Spring Boot 4가 관리하는 Jetty 버전을 바꾸면 애플리케이션 전체 의존성에 영향을 줄 수 있어 WireMock을 standalone JAR로 교체했습니다. WireMock 내부 서버 의존성을 테스트 범위에 격리한 뒤 HTTP 통합 테스트 3개와 전체 테스트 36개가 모두 통과했습니다.

## 2026-09-12 다중 Worker 중복 처리

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2
- 컨테이너: PostgreSQL 17 Alpine, Testcontainers 2.0.5
- 최종 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 최종 결과: 38개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 7개 성공
- 범위: 두 독립 트랜잭션의 동시 작업 선점, 첫 Worker가 전달 중일 때 두 번째 Worker의 중복 선점과 `WebhookHttpClient` 중복 호출 방지, lease token 결과 기록
- 제외: 별도 프로세스나 컨테이너로 실행한 Worker, 외부 인터넷 HTTP 요청, 부하 측정

먼저 두 스레드가 같은 전달 작업을 동시에 선점하도록 실행해 반환된 작업 ID가 하나뿐인지 확인했습니다. Worker 수준 테스트에서는 첫 Worker의 전달 응답을 대기시킨 상태로 두 번째 Worker를 실행했습니다. 두 번째 Worker는 처리할 작업을 가져오지 않았고 `WebhookHttpClient` 호출과 전달 시도 이력도 한 번만 기록됐습니다.

## 2026-09-12 Micrometer 전달 지표

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2
- 컨테이너: PostgreSQL 17 Alpine, Testcontainers 2.0.5
- 최종 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 최종 결과: 42개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 8개 성공
- 범위: Worker 선점 수, 성공·재시도·실패·Dead Letter·lease 상실·예기치 않은 오류별 처리 횟수와 소요 시간, `/actuator/prometheus` 응답
- 제외: Prometheus 서버 수집, Grafana 대시보드, 장시간 실행과 부하 측정

처리 시간은 Micrometer `Timer`로 기록해 횟수와 시간을 함께 확인하도록 구성했습니다. 결과 태그는 여섯 값으로 고정했고 전달 ID, 구독 ID, endpoint URL처럼 값이 계속 늘어날 수 있는 항목은 제외했습니다.

처음 추가한 Prometheus endpoint 테스트는 `AutoConfigureMockMvc`를 찾지 못해 테스트 컴파일에 실패했습니다. Spring Boot 4에서 MVC 테스트 지원이 별도 모듈로 분리된 구성을 확인하고 `spring-boot-starter-webmvc-test`를 테스트 전용 의존성으로 추가했습니다. 운영 의존성에는 영향을 주지 않으면서 실제 HTTP endpoint를 검증할 수 있어 이 방식을 선택했습니다. 수정 후 전체 테스트 42개가 통과했습니다.
