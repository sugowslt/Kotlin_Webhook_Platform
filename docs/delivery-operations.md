# 전달 조회와 재전송

## 목적

운영자는 실패한 전달 ID를 찾고, 현재 상태와 이전 시도 결과를 확인한 뒤 재전송 여부를 판단할 수 있어야 합니다. PostgreSQL에 직접 접속하지 않아도 이 흐름을 처리할 수 있도록 조회 API와 기존 재전송 API를 같은 운영자 권한으로 묶었습니다.

## 인증

아래 경로는 모두 `Authorization: Bearer <token>`이 필요합니다.

- `GET /api/v1/deliveries`
- `GET /api/v1/deliveries/{deliveryId}`
- `POST /api/v1/deliveries/{deliveryId}/redeliveries`

토큰은 `HOOK_RELAY_OPERATOR_TOKEN` 환경변수로 설정합니다. 누락되거나 일치하지 않으면 `401 Unauthorized`로 응답합니다. Compose의 기본 토큰은 loopback 시연 전용이며 다른 환경에서 재사용하지 않습니다.

## 전달 목록 조회

```bash
curl "http://localhost:8080/api/v1/deliveries?status=DEAD_LETTER&limit=30" \
  -H "Authorization: Bearer $HOOK_RELAY_OPERATOR_TOKEN"
```

| 조건 | 내용 |
| --- | --- |
| `status` | 선택값. `PENDING`, `PROCESSING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, `DEAD_LETTER` |
| `limit` | 기본 30, 최소 1, 최대 100 |
| `cursor` | 다음 페이지를 조회할 때 이전 응답의 `nextCursor` 사용 |
| 정렬 | `createdAt` 내림차순, 같은 시각이면 `deliveryId` 내림차순 |

목록은 전체 건수를 세지 않고 `limit + 1`건을 조회해 다음 페이지 존재 여부를 판단합니다. `nextCursor`가 `null`이면 마지막 페이지입니다. cursor에는 마지막 항목의 생성 시각, 전달 ID, 상태 필터가 URL-safe Base64로 들어가며 클라이언트가 내부 형식에 의존하지 않도록 불투명한 값으로 다룹니다.

상태 필터가 다른 요청에 cursor를 재사용하거나 cursor 형식이 잘못되면 `400 Bad Request`로 응답합니다. 키셋 페이지 방식을 사용하므로 앞 페이지를 조회한 뒤 새 전달이 추가돼도 offset 이동으로 인한 중복을 만들지 않습니다.

## 전달 상세 조회

```bash
curl "http://localhost:8080/api/v1/deliveries/{deliveryId}" \
  -H "Authorization: Bearer $HOOK_RELAY_OPERATOR_TOKEN"
```

상세 응답에는 아래 정보가 포함됩니다.

- 전달·이벤트·구독 ID와 이벤트 유형
- 현재 상태, 완료된 시도 횟수, 다음 시도 시각, lease 만료 시각
- 마지막 오류와 생성·수정 시각
- 시도 번호, 결과, HTTP 상태, 오류, 시작·완료 시각

이벤트 payload, Webhook endpoint URL, 서명 비밀값은 조회 응답에 포함하지 않습니다. 운영자 API가 필요 이상으로 원문 데이터와 자격 정보를 반환하지 않도록 범위를 제한했습니다.

없는 전달 ID는 기존 재전송 API와 같은 `DELIVERY_NOT_FOUND` 코드와 `404 Not Found`로 응답합니다.

## PostgreSQL 조회 경로

목록은 `webhook_deliveries`를 생성 시각과 UUID로 정렬합니다. 전체 목록과 상태별 목록이 같은 정렬 순서로 cursor를 사용할 수 있도록 Flyway V3에서 아래 인덱스를 추가했습니다.

- `idx_webhook_deliveries_created(created_at DESC, id DESC)`
- `idx_webhook_deliveries_status_created(status, created_at DESC, id DESC)`

상세 조회는 전달 1건을 읽은 뒤 같은 읽기 전용 트랜잭션에서 시도 이력을 `attempt_number` 순서로 조회합니다.

## 검증 결과

2026-09-15 로컬 Java 17과 PostgreSQL 17 Testcontainers 환경에서 다음 항목을 확인했습니다.

- 운영자 토큰이 없는 목록·상세 요청 `401`
- 최신순 목록과 두 페이지 사이 전달 ID 중복 없음
- 대소문자를 구분하지 않는 상태 필터
- 상세 상태와 시도 이력 일치
- payload, endpoint URL, 서명 비밀값 미노출
- 없는 전달 ID `404`
- 잘못된 상태·limit·cursor `400`
- Flyway V3 인덱스 2개 생성

PostgreSQL 통합 테스트 21개를 포함한 전체 테스트 64개가 통과했고 실패·오류·skipped는 없었습니다. Compose에서도 기존 volume에 V3를 적용한 뒤 무인증 목록 `401`, 인증 목록과 상세 `200`을 확인했습니다.

## 참고 기준

- [GitHub REST Webhook delivery API](https://docs.github.com/en/rest/repos/webhooks)
- [GitHub REST API pagination](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)
