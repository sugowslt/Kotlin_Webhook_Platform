# Docker Compose 로컬 시연

## 구성

`compose.yaml`은 아래 서비스를 함께 실행합니다.

| 서비스 | 역할 | 로컬 주소 |
| --- | --- | --- |
| `app` | 이벤트 접수와 전달 Worker | `http://127.0.0.1:8080` |
| `postgres` | 이벤트·전달 작업·시도 이력 저장 | 호스트에 공개하지 않음 |
| `webhook-receiver` | 전달 요청 수신과 헤더·본문 로그 출력 | 호스트에 공개하지 않음 |
| `prometheus` | 5초 간격 지표 수집 | `http://127.0.0.1:9090` |
| `grafana` | 전달 상태와 처리 지표 시각화 | `http://127.0.0.1:3000` |

PostgreSQL과 Webhook 수신기의 health check가 통과해야 애플리케이션을 시작합니다. Prometheus는 애플리케이션이 healthy 상태가 된 뒤 기동합니다.

## 실행

Docker Desktop을 실행하고 저장소 루트에서 아래 스크립트를 실행합니다.

### 기본 전달

```powershell
.\demo\run-demo.ps1
```

스크립트가 수행하는 범위는 다음과 같습니다.

1. `docker compose up --build --wait`로 5개 서비스를 기동합니다.
2. 로컬 Webhook 수신 구독을 등록합니다.
3. 고유 멱등키로 `demo.created` 이벤트를 접수합니다.
4. 해당 이벤트의 모든 전달 작업이 `SUCCEEDED`가 될 때까지 최대 30초 확인합니다.
5. 성공 지표와 Webhook 수신 로그를 출력합니다.

### 수동 재전송

```powershell
.\demo\run-redelivery-demo.ps1
```

스크립트가 수행하는 범위는 다음과 같습니다.

1. 5개 서비스를 기동하고 Webhook 수신기를 최신 코드로 다시 시작합니다.
2. 반복 실행 시 기존 구독과 섞이지 않도록 고유 이벤트 유형을 등록합니다.
3. 수신기가 첫 요청에 `400`을 반환해 전달이 `FAILED`가 되는지 확인합니다.
4. 운영자 Bearer 토큰으로 `POST /api/v1/deliveries/{deliveryId}/redeliveries`를 호출합니다.
5. 같은 전달 ID의 두 번째 요청이 `204`로 끝나는지 확인합니다.
6. 최종 상태 `SUCCEEDED`, 시도 횟수 2회, 이력 `FAILED,SUCCEEDED`를 대조합니다.

### Worker 강제 종료 복구

```powershell
.\demo\run-crash-recovery-demo.ps1
```

스크립트는 실제 프로세스 중단 상황을 아래 순서로 재현합니다.

1. 실행마다 고유 이벤트 유형과 60초 동안 첫 응답을 보류하는 구독을 만듭니다.
2. 수신 로그와 DB에서 전달이 `PROCESSING`인지 확인합니다.
3. `docker compose kill app`으로 애플리케이션에 `SIGKILL`을 보냅니다.
4. 전달 상태가 `PROCESSING`, 시도 횟수와 완료 이력이 0건인 상태로 남았는지 확인합니다.
5. 애플리케이션을 다시 시작하고 30초 lease가 만료될 때까지 기다립니다.
6. 재선점 시각이 lease 만료 이후인지, 최종 상태와 이력이 `SUCCEEDED|1|SUCCEEDED`인지 검사합니다.
7. 같은 전달 ID가 수신기에 `HELD`, `204` 두 번 기록됐는지 확인합니다.

이 시나리오는 PostgreSQL에 저장한 작업이 Worker 종료 후 복구되는지 확인합니다. 첫 요청이 외부 서버에 도착한 뒤 결과 기록 전에 Worker가 종료되므로 수신기에는 같은 전달 ID가 두 번 도착합니다. 전달 대상은 `X-HookRelay-Delivery`를 멱등키로 사용해 중복 처리를 막아야 합니다.

정상 실행 후 아래 화면을 확인할 수 있습니다.

- Grafana: [Hook Relay Overview](http://127.0.0.1:3000/d/hook-relay-overview)
- Prometheus: [Targets](http://127.0.0.1:9090/targets)
- 애플리케이션: [Health](http://127.0.0.1:8080/actuator/health)

Grafana 대시보드는 애플리케이션 상태, 선점한 전달 수, 작업 대기열 상태, 선점 SQL 평균 실행 시간, 성공·Dead Letter 건수, 결과별 처리율과 평균 처리 시간을 보여줍니다. 모두 8개 패널이며 데이터 소스와 대시보드는 파일로 provisioning하므로 별도 설정이나 로그인은 필요하지 않습니다.

작업 대기열은 `claimable`, `scheduled`, `leased`, `stalled`로 구분합니다. 여러 애플리케이션 인스턴스가 같은 PostgreSQL 작업 수를 각각 노출해도 합산되지 않도록 Grafana 쿼리는 상태별 최댓값을 사용합니다.

## 보안 경계

기본 애플리케이션은 HTTPS와 공개 주소만 Webhook 대상으로 허용합니다. Compose 환경은 내부 `webhook-receiver`에 요청을 보내기 위해 아래 설정을 로컬 시연에만 적용합니다.

```yaml
HOOK_RELAY_SECURITY_ALLOWED_SCHEMES: http,https
HOOK_RELAY_SECURITY_ALLOW_NON_PUBLIC_TARGETS: "true"
HOOK_RELAY_OPERATOR_TOKEN: ${HOOK_RELAY_OPERATOR_TOKEN:-local-demo-operator-token-not-secret}
```

애플리케이션·Prometheus·Grafana 포트는 `127.0.0.1`에만 바인딩했습니다. PostgreSQL과 Webhook 수신기는 호스트에 포트를 공개하지 않습니다. Grafana 익명 접근도 로컬 시연 화면을 바로 확인하기 위한 설정입니다.

`local-demo-operator-token-not-secret`은 시연 스크립트와 Compose를 바로 연결하기 위한 공개값입니다. 운영 환경에서는 재사용하지 않습니다. 다른 토큰으로 시연하려면 32자 이상의 값을 Compose 환경변수에 넣고 스크립트에도 같은 값을 전달합니다.

```powershell
.\demo\run-redelivery-demo.ps1 -OperatorToken "local-test-operator-token-replace-me"
```

수동 재전송 API는 Bearer 토큰이 일치하는 요청에만 `OPERATOR` 권한을 부여합니다. 누락하거나 잘못 보낸 토큰은 같은 `401 Unauthorized` 응답으로 처리하며 인증 상태를 세션에 저장하지 않습니다. 외부 환경에서는 이 인증과 함께 TLS와 네트워크 접근 제한을 적용해야 합니다.

## 종료와 초기화

컨테이너를 종료하고 네트워크를 제거합니다. 저장된 데이터는 유지됩니다.

```powershell
docker compose down
```

시연 데이터를 모두 지우고 처음부터 실행해야 할 때만 volume을 함께 제거합니다.

```powershell
docker compose down -v
```
