package com.sugowslt.hookrelay.delivery

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface DeliveryRedeliveryStore {
    fun requeue(deliveryId: UUID, requestedAt: Instant): DeliveryRedeliveryResult
}

sealed interface DeliveryRedeliveryResult {
    data class Requeued(
        val completedAttempts: Int,
    ) : DeliveryRedeliveryResult

    data class NotRedeliverable(
        val status: DeliveryStatus,
    ) : DeliveryRedeliveryResult

    data object NotFound : DeliveryRedeliveryResult
}

@Repository
class JdbcDeliveryRedeliveryStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DeliveryRedeliveryStore {
    @Transactional
    override fun requeue(deliveryId: UUID, requestedAt: Instant): DeliveryRedeliveryResult {
        val parameters = MapSqlParameterSource()
            .addValue("deliveryId", deliveryId)
            .addValue("requestedAt", Timestamp.from(requestedAt))
        val completedAttempts = jdbcTemplate.query(REQUEUE_SQL, parameters) { resultSet, _ ->
            resultSet.getInt("attempt_count")
        }.singleOrNull()

        if (completedAttempts != null) {
            return DeliveryRedeliveryResult.Requeued(completedAttempts)
        }

        val status = jdbcTemplate.query(STATUS_SQL, parameters) { resultSet, _ ->
            DeliveryStatus.valueOf(resultSet.getString("status"))
        }.singleOrNull()
        return status?.let { DeliveryRedeliveryResult.NotRedeliverable(it) }
            ?: DeliveryRedeliveryResult.NotFound
    }

    companion object {
        private val REQUEUE_SQL = """
            UPDATE webhook_deliveries
            SET status = 'PENDING',
                next_attempt_at = :requestedAt,
                lease_until = NULL,
                lease_token = NULL,
                last_error = NULL,
                updated_at = :requestedAt
            WHERE id = :deliveryId
              AND status IN ('FAILED', 'DEAD_LETTER')
            RETURNING attempt_count
        """.trimIndent()

        private val STATUS_SQL = """
            SELECT status
            FROM webhook_deliveries
            WHERE id = :deliveryId
        """.trimIndent()
    }
}

@Service
class DeliveryRedeliveryService(
    private val redeliveryStore: DeliveryRedeliveryStore,
    private val clock: Clock,
) {
    fun request(deliveryId: UUID): AcceptedRedelivery {
        val requestedAt = Instant.now(clock)
        return when (val result = redeliveryStore.requeue(deliveryId, requestedAt)) {
            is DeliveryRedeliveryResult.Requeued -> AcceptedRedelivery(
                deliveryId = deliveryId,
                status = DeliveryStatus.PENDING,
                completedAttempts = result.completedAttempts,
                requestedAt = requestedAt,
            )

            is DeliveryRedeliveryResult.NotRedeliverable -> throw DeliveryNotRedeliverableException(
                deliveryId = deliveryId,
                status = result.status,
            )

            DeliveryRedeliveryResult.NotFound -> throw DeliveryNotFoundException(deliveryId)
        }
    }
}

data class AcceptedRedelivery(
    val deliveryId: UUID,
    val status: DeliveryStatus,
    val completedAttempts: Int,
    val requestedAt: Instant,
)

class DeliveryNotFoundException(
    deliveryId: UUID,
) : RuntimeException("Delivery was not found: $deliveryId") {
    val errorCode = "DELIVERY_NOT_FOUND"
}

class DeliveryNotRedeliverableException(
    deliveryId: UUID,
    val status: DeliveryStatus,
) : RuntimeException("Delivery cannot be redelivered while status is $status: $deliveryId") {
    val errorCode = "DELIVERY_NOT_REDELIVERABLE"
}

@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryRedeliveryController(
    private val redeliveryService: DeliveryRedeliveryService,
) {
    @PostMapping("/{deliveryId}/redeliveries")
    fun request(
        @PathVariable deliveryId: UUID,
    ): ResponseEntity<AcceptedRedelivery> = ResponseEntity.accepted()
        .body(redeliveryService.request(deliveryId))
}
