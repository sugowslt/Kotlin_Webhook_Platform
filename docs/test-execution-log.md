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

## 2026-09-12 이벤트 접수 부하 측정

- 환경: Windows, Java 17.0.18, k6 2.1.0, Docker 29.7.2
- 호스트: Intel Core Ultra 7 258V 8코어·8스레드, RAM 31.51GiB
- 데이터베이스: PostgreSQL 17.11, 새 데이터베이스
- 부하 조건: 준비 초당 20건 10초, 측정 초당 50건 60초, 측정 VU 50개 사전 할당
- 측정 결과: 3,001건, 평균 15.47ms, p50 13.77ms, p95 28.20ms, p99 49.73ms, 최대 111.67ms
- 기능 검사: 9,003개 성공, 실패 0개
- 오류: 0건
- dropped iteration: 0건
- DB 대조: 이벤트 3,201건, 전달 작업 3,201건, 고유 멱등키 3,201개
- 제외: Worker HTTP 전달, 재시도, 장시간 부하, 운영 환경 용량 추정

한 번의 이벤트 접수에서 구독 조회와 전달 작업 1건 저장까지 실행되도록 활성 구독을 1개 등록했습니다. Worker 주기는 1시간으로 늘려 측정 중 HTTP 전달이 실행되지 않도록 했고 DB에서 모든 전달 작업이 `PENDING`, 전달 시도 이력이 0건인 것을 확인했습니다.

상세 조건과 재현 명령은 [이벤트 접수 부하 측정](load-test.md)에 기록했습니다.

## 2026-09-12 Docker Compose 로컬 시연

- 환경: Windows, Java 17.0.18, Docker 29.7.2, Docker Compose 5.5.1
- 애플리케이션 이미지: Gradle 9.3.1 JDK 17 빌드, Eclipse Temurin 17 JRE 실행
- 구성: 애플리케이션, PostgreSQL 17, Python 3.13 Webhook 수신기, Prometheus 3.13.3, Grafana 12.3.1
- 실행 명령: `.\demo\run-demo.ps1`
- 전체 테스트: 43개 성공, 실패 0개, 오류 0개, skipped 0개
- 서비스 상태: 5개 기동, 애플리케이션·PostgreSQL·Webhook 수신기·Prometheus·Grafana 정상
- 전달 검증: 구독 1개 등록, 이벤트 1개 접수, 전달 작업 1개 `SUCCEEDED`
- 관측 검증: Prometheus target `up`, Grafana `Hook Relay Overview` 6개 패널 렌더링
- 격리 확인: 애플리케이션 이미지는 `hookrelay` 사용자로 실행, 호스트 공개 포트는 `127.0.0.1`에만 바인딩

첫 빌드는 Gradle 사용자가 `/workspace/.gradle`을 만들 수 없어 중단됐습니다. 작업 경로를 Gradle 홈 아래로 옮긴 두 번째 시도도 Docker가 새 하위 디렉터리를 root 소유로 생성해 같은 오류가 발생했습니다. 경로를 바꾸는 대신 빌드 단계에서 `/workspace` 소유권만 `gradle` 사용자에게 부여했습니다. 빌드 사용자에게 추가 권한을 주지 않으면서 캐시와 산출물을 쓸 수 있어 이 방식을 사용했습니다.

이미지 빌드 후에는 Webhook 수신기가 실행 중인데도 health check의 `wget`이 `localhost:8081` 연결을 거부해 unhealthy 상태가 됐습니다. 컨테이너 안에서 `127.0.0.1:8081/health`가 정상 응답하는 것을 확인하고 health check 주소를 IPv4 loopback으로 고정했습니다. 수정 후 5개 서비스가 모두 기동됐고 구독 등록, 이벤트 접수, HMAC 헤더가 포함된 실제 로컬 전달, DB 상태, Prometheus 수집, Grafana 화면을 순서대로 확인했습니다.

## 2026-09-12 수동 재전송

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2
- 통합 테스트 명령: `gradlew.bat test --tests com.sugowslt.hookrelay.integration.PostgreSqlIntegrationTest`
- 전체 테스트 명령: `gradlew.bat test`
- 전체 테스트 결과: 47개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 12개 성공
- 시연 명령: `.\demo\run-redelivery-demo.ps1`
- 시연 결과: 첫 요청 `400/FAILED`, 수동 재전송 `202/PENDING`, 두 번째 요청 `204/SUCCEEDED`
- DB 대조: 동일 전달 ID, 시도 횟수 2회, 이력 `FAILED,SUCCEEDED`
- 동시성 검증: 같은 전달에 대한 동시 요청 2건 중 상태 전환 1건만 성공

`FAILED`와 `DEAD_LETTER`만 조건부 `UPDATE`로 `PENDING` 전환합니다. 같은 시점에 요청이 겹쳐도 첫 요청만 상태를 바꾸고, 나머지는 현재 상태를 읽어 `409 Conflict`로 끝냅니다. 기존 전달 ID와 시도 횟수는 유지해 감사 이력의 순서를 보존했습니다.

첫 컴파일에서는 새 예외의 nullable `message`를 오류 응답의 non-null 필드에 넘겨 실패했습니다. 예외 코드별 기본 메시지를 두어 응답 계약을 유지했습니다.

첫 Compose 시연에서는 bind mount 파일이 바뀌었지만 실행 중인 Python 수신기 프로세스는 이전 코드를 유지해 재전송도 `404`로 끝났습니다. 시연 스크립트에서 수신기만 명시적으로 재시작하고 health check를 다시 기다리도록 수정했습니다. 다음 실행에서는 이전 시연의 고정 이벤트 유형 구독이 남아 전달 ID가 여러 개 조회됐습니다. 데이터를 지우는 대신 실행마다 고유 이벤트 유형을 만들고 단일값 조회 결과 개수를 검사하도록 바꿨습니다. 보완 후 수동 재전송 시연을 연속 두 번 실행했고 두 번 모두 `FAILED,SUCCEEDED` 이력을 확인했습니다. 기존 `run-demo.ps1` 성공 경로도 다시 실행해 정상 완료를 확인했습니다.

## 2026-09-12 Worker 강제 종료 복구

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2, Docker Compose 5.5.1
- 전체 테스트 명령: `gradlew.bat test --rerun-tasks`
- 전체 테스트 결과: 47개 성공, 실패 0개, 오류 0개, skipped 0개
- 시연 명령: `.\demo\run-crash-recovery-demo.ps1`
- 종료 방식: 전달 요청 응답 대기 중 `docker compose kill app`의 기본 `SIGKILL`
- 종료 직후 DB 상태: `PROCESSING`, 시도 횟수 0회, 완료 이력 0건
- 복구 조건: 30초 lease 만료 이후 같은 전달 ID 재선점
- 최종 DB 상태: `SUCCEEDED`, 시도 횟수 1회, 이력 `SUCCEEDED`
- 수신기 대조: 같은 전달 ID의 요청 2건, 첫 요청 `HELD`, 복구 요청 `204`

강제 종료 복구 시연은 연속 두 번 실행했습니다. 두 실행 모두 재선점 시각이 기존 lease 만료 시각보다 빠르지 않았고 최종 상태가 `SUCCEEDED|1|SUCCEEDED`로 끝났습니다. 수신기에는 같은 전달 ID가 두 번 남아 at-least-once 동작도 확인했습니다.

수신기 변경 후 `.\demo\run-redelivery-demo.ps1`과 `.\demo\run-demo.ps1`도 다시 실행했습니다. 수동 재전송은 `FAILED,SUCCEEDED`, 기본 전달은 모든 작업이 `SUCCEEDED`로 끝났습니다. 시연 중 PostgreSQL과 Docker volume은 삭제하지 않았습니다.

## 2026-09-13 수동 재전송 운영자 인증

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2, Docker Compose 5.5.1
- 전체 테스트 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 전체 테스트 결과: 52개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 14개 성공
- 토큰 검증 단위 테스트: 3개 성공
- 시연 명령: `.\demo\run-redelivery-demo.ps1`
- 시연 결과: 인증된 재전송 `202/PENDING`, 최종 상태 `SUCCEEDED`, 이력 `FAILED,SUCCEEDED`
- 인증 응답: 토큰 누락 `401`, 잘못된 토큰 `401`, 정상 토큰으로 없는 전달 요청 `404`
- 로그 검사: 로컬 시연용 운영자 토큰 문자열 노출 0건
- 제외: 운영자별 계정, 무중단 토큰 교체, 외부 OIDC, TLS 종단

수동 재전송 경로에만 Spring Security 요청 권한 검사를 적용했습니다. `Authorization: Bearer` 토큰이 일치하면 `OPERATOR` 권한을 부여하고, 인증 상태는 HTTP 세션에 저장하지 않습니다. 토큰은 SHA-256 digest로 바꾼 뒤 상수 시간 비교를 사용합니다.

누락과 불일치 응답을 같은 오류 코드로 맞춰 인증 실패 원인을 외부에 구분해 주지 않았습니다. 정상 토큰으로 존재하지 않는 전달을 요청했을 때는 기존 `404 DELIVERY_NOT_FOUND`가 반환되어 인증 이후 Controller의 오류 계약도 유지됐습니다. 재전송 시연 과정에서 구독 등록과 이벤트 접수도 각각 `201`, `202`로 처리되어 공개 API의 기존 동작을 함께 확인했습니다.

## 2026-09-13 작업 대기열 관측

- 환경: Windows, Java 17.0.18, Gradle Wrapper 9.3.1, Docker 29.7.2, Docker Compose 5.5.1
- 전체 테스트 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 전체 테스트 결과: 56개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 15개 성공
- 시연 명령: `.\demo\run-demo.ps1`
- 전달 결과: 전달 작업 4개 모두 `SUCCEEDED`
- Prometheus 확인: 작업 대기열 상태 시계열 4개, 선점률 집계 시계열 1개
- Grafana 확인: dashboard version 2, 패널 8개
- 제외: 경보 임계값, 장시간 추이, 다중 인스턴스 부하, 운영 환경 DB 비용

작업 대기열은 `claimable`, `scheduled`, `leased`, `stalled`로 집계합니다. `claimable`은 `PENDING`·`RETRY_WAIT` 중 실행 시각이 지난 작업과 lease가 만료된 `PROCESSING` 작업입니다. 미래에 실행할 작업은 `scheduled`, 유효한 lease가 있으면 `leased`, `PROCESSING`인데 lease가 없으면 `stalled`로 분류했습니다. PostgreSQL 통합 테스트에서는 네 상태를 각각 만든 뒤 `2, 1, 1, 0`으로 집계되는지 확인했습니다.

Prometheus가 `/actuator/prometheus`를 호출할 때마다 DB 쿼리가 실행되지 않도록 별도 sampler가 5초마다 상태를 읽어 메모리 Gauge를 갱신합니다. 같은 DB를 보는 애플리케이션이 여러 개면 전역 작업 수가 인스턴스마다 반복되므로 Grafana에서는 `sum` 대신 상태별 `max`를 사용했습니다. 선점 SQL은 성공과 실패를 고정된 outcome 태그로 나눠 Timer에 기록합니다.

## 2026-09-13 작업 대기열 적체 관측

- 환경: Windows, Java 17.0.18, Docker 29.7.2, Docker Compose 5.5.1
- 전체 테스트 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 전체 테스트 결과: 56개 성공, 실패 0개, 오류 0개, skipped 0개
- 시연 명령: `.\demo\run-backlog-observability-demo.ps1`
- 조건: 이벤트 100건, 전달 작업 100건, 수신 응답 지연 250ms, Worker batch 20건
- 접수 시간: 1.86초
- 전체 배수 시간: 34.08초
- PostgreSQL 최대값: `claimable=80`, `leased=20`
- Prometheus 최대값: `claimable=80`, `leased=17`
- 선점 Timer: 8회, 평균 12.685ms
- DB 대조: 이벤트 100건, 전달 100건, 시도 이력 100건, 실패·정체 0건
- 종료 상태: 활성 전달 작업 없음, 네 작업 대기열 Gauge 모두 0
- 제외: 운영 처리량 추정, 다중 Worker, 장시간 추이, 운영 경보 임계값

첫 실행은 이벤트·전달·시도 이력 100건이 모두 일치했지만 PostgreSQL의 `leased` 최대값 20건과 달리 Prometheus에서는 0으로 관측됐습니다. 기본 단일 스케줄러 스레드에서 Worker와 Gauge sampler가 함께 실행돼, Worker가 batch를 처리하는 동안 sampler가 기다린 것이 원인이었습니다.

`spring.task.scheduling.pool.size`를 2로 설정해 Worker와 sampler가 독립적으로 실행되도록 바꿨습니다. 같은 조건으로 다시 측정하자 PostgreSQL과 Prometheus에서 `claimable` 최대 80건이 일치했고 Prometheus에서도 `leased` 최대 17건을 확인했습니다. 두 값의 차이는 PostgreSQL 1초, Gauge 5초인 표본 주기에서 발생했습니다.

## 2026-09-13 Prometheus 경보 규칙

- 구성 검사: `docker compose config --quiet`
- 규칙 검사: `promtool check rules`, 3개 성공
- 규칙 테스트: `promtool test rules`, 4개 시나리오 성공
- 실제 로드 결과: 규칙 3개 모두 `health=ok`, `state=inactive`, 활성 경보 0개
- 검증 조건: 정상 상태, 전체 수집 대상 중단, lease 없는 처리 작업, 선점 쿼리 실패
- 제외: Alertmanager, 이메일·메신저 알림, 운영 환경 임계값

첫 검사 명령은 Prometheus 이미지의 기본 entrypoint가 `prometheus`인 상태에서 `promtool`을 하위 명령처럼 전달해 `unexpected promtool`로 끝났습니다. `--entrypoint promtool`을 명시해 이미지에 포함된 검사 도구를 직접 실행했고 규칙 3개와 단위 테스트가 모두 통과했습니다.

경보는 모든 애플리케이션 수집 대상이 1분 이상 중단된 경우, lease 없는 `PROCESSING` 작업이 1분 이상 남은 경우, 최근 5분 선점 실패가 1분 이상 계속 관측된 경우로 제한했습니다. 로컬 적체 수치는 운영 SLO와 유입량을 반영하지 않으므로 작업 건수 임계값에는 사용하지 않았습니다.

## 2026-09-15 최종 회귀 검증

- 전체 테스트 명령: `gradlew.bat test check --rerun-tasks --no-daemon`
- 전체 테스트 결과: 56개 성공, 실패 0개, 오류 0개, skipped 0개
- 기본 전달: 기존 구독을 포함한 전달 작업 6개 모두 `SUCCEEDED`
- 수동 재전송: 동일 전달 ID, 시도 횟수 2회, 이력 `FAILED,SUCCEEDED`
- Worker 강제 종료 복구: 종료 직후 `PROCESSING|0|0`, lease 만료 후 `SUCCEEDED|1|SUCCEEDED`
- 복구 수신 기록: 동일 전달 ID 요청 2건, 첫 요청 `HELD`, 두 번째 요청 `204`
- Prometheus 규칙 검사: 3개 성공
- Prometheus 규칙 테스트: 4개 시나리오 성공
- 최종 상태: 경보 3개 모두 `health=ok`, `inactive`, 활성 전달 작업 없음

기본 전달 시연은 volume에 남아 있던 `demo.created` 구독 5개와 이번 실행에서 만든 구독 1개가 함께 동작했습니다. 스크립트가 API의 `deliveryCount=6`을 기준으로 DB 성공 건수를 대조했고 6개 모두 완료됐습니다. 수동 재전송과 강제 종료 복구는 실행마다 고유 이벤트 유형을 사용해 이전 데이터와 분리했습니다.

## 2026-09-15 Worker HTTP 전달 처리량 측정

- 환경: Windows 11, Java 17.0.18, Gradle 9.3.1, Docker 29.7.2, Docker Compose 5.5.1
- 전체 테스트 명령: `gradlew.bat test --rerun-tasks`
- 전체 테스트 결과: 56개 성공, 실패 0개, 오류 0개, skipped 0개
- 측정 명령: `.\demo\run-worker-throughput-demo.ps1`
- 적재 조건: k6 `shared-iterations`, VU 50개, 매회 이벤트 500건
- Worker 조건: 단일 인스턴스, batch 20건, fixed delay 1초, lease 30초
- 수신 조건: 로컬 수신기, 응답 지연 0ms, `204 No Content`
- 처리량: 17.85건/초, 17.88건/초, 17.79건/초
- 평균·중앙값·범위: 17.84건/초, 17.85건/초, 17.79~17.88건/초
- 처리 구간: 28.006초, 27.967초, 28.104초
- 전달 1건 평균: 7.045ms, 7.010ms, 7.282ms
- 회차별 대조: 이벤트·전달·시도 이력·수신 요청 각각 500건
- k6 결과: 회차별 기능 검사 1,500개 성공, HTTP 오류 0건, dropped iteration 0건
- 종료 상태: Worker 주기 1초, 수신기 지연 250ms 복구, 활성 전달 작업 0건
- 제외: 다중 Worker, 외부 네트워크와 TLS, 수신 지연, 재시도·timeout, 운영 용량 추정

처리 구간은 각 회차의 첫 시도 `started_at`부터 마지막 시도 `finished_at`까지 계산했습니다. 애플리케이션 컨테이너 재시작과 health check가 포함된 전체 경과 시간은 처리량 산정에서 제외했습니다.

초기 3회 측정 뒤 이벤트 건수도 전달 건수와 별도로 대조하도록 스크립트를 보완하고 같은 조건으로 3회 다시 실행했습니다. 최종 결과는 보완 후 측정값만 기록했습니다. 전달 1건의 내부 처리 시간은 평균 약 7.1ms였지만 batch 20건을 처리한 뒤 fixed delay 1초가 적용돼 유효 처리량은 평균 17.84건/초였습니다.

상세 조건과 재현 방법은 [Worker HTTP 전달 처리량 측정](worker-throughput.md)에 기록했습니다.

## 2026-09-15 다중 Worker 확장 측정

- 전체 테스트 명령: `gradlew.bat test --rerun-tasks`
- 전체 테스트 결과: 58개 성공, 실패 0개, 오류 0개, skipped 0개
- 측정 명령: `.\demo\run-worker-scaling-demo.ps1`
- 측정 조합: 대기열 500건·1,000건, Worker 1개·2개·4개, 조합별 유효 결과 3회
- Worker 조건: 인스턴스당 batch 20건, fixed delay 1초, lease 30초
- 수신 조건: 로컬 수신기, 응답 지연 0ms, `204 No Content`
- Worker 1개 평균: 500건 17.59건/초, 1,000건 17.44건/초
- Worker 2개 평균: 500건 34.51건/초, 1,000건 34.97건/초
- Worker 4개 평균: 500건 60.95건/초, 1,000건 65.12건/초
- Worker 4개 배율: Worker 1개 대비 3.46배와 3.73배
- 유효 회차 대조: 이벤트·전달·성공 시도·수신 요청 수가 대기열 크기와 일치, 비성공 시도 0건
- 종료 상태: 임시 Worker 0개, 기본 API Worker와 수신기 지연 복구, 활성 전달 작업 0건

Worker 활성 조건 테스트를 처음 일반 `@Configuration`으로 선언했을 때 PostgreSQL 통합 테스트 15개가 `clock` bean 중복으로 실패했습니다. 테스트 전용 설정을 `@TestConfiguration`으로 바꾼 뒤 전체 58개 테스트가 통과했습니다.

첫 Worker 2개 측정에서는 DB 성공 500건과 달리 수신 로그가 492줄만 집계됐습니다. Python `ThreadingHTTPServer`의 여러 요청 스레드가 표준 출력에 동시에 기록하며 줄 경계가 합쳐졌습니다. 출력 구간을 하나의 lock으로 보호하고 같은 조건을 다시 실행해 DB와 로그 500건이 일치하는 것을 확인했습니다.

1,000건·Worker 4개 한 회차에서 첫 시도 1건이 `HTTP/1.1 header parser received no bytes`로 끝나 재시도 대상으로 분류됐습니다. 해당 요청은 이후 두 번째 시도에서 `204`로 성공했지만 성공 응답만 비교하는 조건과 달라 그 회차를 제외했습니다. 같은 조건으로 다시 측정한 결과 이벤트·전달·성공 시도·수신 요청이 각각 1,000건이고 비성공 시도는 0건이었습니다. 전체 확장 측정 이력에서 같은 오류는 1건뿐이므로 반복 장애로 단정하지 않았습니다.

측정 후 회귀 검증으로 기본 전달, 수동 재전송, Worker 강제 종료 복구 시나리오를 다시 실행했습니다. 기본 전달은 전달 7건이 모두 성공했고, 수동 재전송은 `FAILED,SUCCEEDED`, 강제 종료 복구는 lease 만료 후 `SUCCEEDED|1|SUCCEEDED`로 끝났습니다.

상세 조건과 회차별 결과는 [다중 Worker 확장 측정](worker-scaling.md)에 기록했습니다.

## 2026-09-15 전달 운영 조회 API

- 전체 테스트 명령: `gradlew.bat test --rerun-tasks`
- 전체 테스트 결과: 64개 성공, 실패 0개, 오류 0개, skipped 0개
- PostgreSQL 통합 테스트: 21개 성공
- Flyway: V3 목록 정렬·상태 필터 인덱스 2개 생성
- 인증: 무인증 목록·상세 요청 `401 Unauthorized`
- 목록: 최신순 정렬, 상태 필터, limit 최대 100, cursor 페이지 사이 중복 없음
- 상세: 현재 상태와 시도 이력 일치, 없는 전달 `404 Not Found`
- 입력 오류: 잘못된 상태·limit·cursor `400 Bad Request`
- 응답 경계: payload, endpoint URL, 서명 비밀값 제외
- Compose 확인: 기존 volume에 V3 적용, 무인증 목록 `401`, 인증 목록·상세 `200`

offset 대신 생성 시각과 전달 UUID를 묶은 keyset cursor를 사용했습니다. 전체 행 수를 매번 세지 않고 `limit + 1`건을 조회해 다음 페이지 존재 여부만 판단합니다. cursor에는 상태 필터도 함께 넣어 다른 조회 조건에 재사용하면 요청을 거부합니다.

조회 API는 기존 재전송 API와 같은 `OPERATOR` 권한으로 보호했습니다. 목록과 상세 응답에는 재전송 판단에 필요한 상태·오류·시도 결과만 포함하고 원본 payload와 Webhook endpoint, 서명 비밀값은 제외했습니다.

상세 동작과 요청 예시는 [전달 조회와 재전송](delivery-operations.md)에 기록했습니다.
