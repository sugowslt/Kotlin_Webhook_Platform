package com.sugowslt.hookrelay.delivery

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface DeliveryQueue {
    fun claim(batchSize: Int, leaseDuration: Duration, now: Instant): List<ClaimedDelivery>

    fun recordResult(delivery: ClaimedDelivery, resolution: DeliveryResolution, finishedAt: Instant): Boolean
}

data class ClaimedDelivery(
    val id: UUID,
    val eventId: UUID,
    val eventType: String,
    val payload: String,
    val subscriptionId: UUID,
    val endpointUrl: String,
    val signingSecret: String,
    val completedAttempts: Int,
    val leaseToken: UUID,
    val claimedAt: Instant,
)

sealed interface DeliveryResolution {
    val statusCode: Int?
    val errorMessage: String?

    data class Succeeded(
        override val statusCode: Int,
    ) : DeliveryResolution {
        override val errorMessage: String? = null
    }

    data class RetryAt(
        val nextAttemptAt: Instant,
        override val statusCode: Int?,
        override val errorMessage: String?,
    ) : DeliveryResolution

    data class Failed(
        override val statusCode: Int?,
        override val errorMessage: String,
    ) : DeliveryResolution

    data class DeadLetter(
        override val statusCode: Int?,
        override val errorMessage: String,
    ) : DeliveryResolution
}

@Repository
class JdbcDeliveryQueue(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DeliveryQueue {
    @Transactional
    override fun claim(batchSize: Int, leaseDuration: Duration, now: Instant): List<ClaimedDelivery> {
        require(batchSize in 1..100) { "batchSize must be between 1 and 100" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }

        val leaseToken = UUID.randomUUID()
        val parameters = MapSqlParameterSource()
            .addValue("now", Timestamp.from(now))
            .addValue("leaseUntil", Timestamp.from(now.plus(leaseDuration)))
            .addValue("leaseToken", leaseToken)
            .addValue("batchSize", batchSize)

        return jdbcTemplate.query(CLAIM_SQL, parameters) { resultSet, _ ->
            ClaimedDelivery(
                id = resultSet.getObject("id", UUID::class.java),
                eventId = resultSet.getObject("event_id", UUID::class.java),
                eventType = resultSet.getString("event_type"),
                payload = resultSet.getString("payload"),
                subscriptionId = resultSet.getObject("subscription_id", UUID::class.java),
                endpointUrl = resultSet.getString("endpoint_url"),
                signingSecret = resultSet.getString("signing_secret"),
                completedAttempts = resultSet.getInt("attempt_count"),
                leaseToken = resultSet.getObject("lease_token", UUID::class.java),
                claimedAt = now,
            )
        }
    }

    @Transactional
    override fun recordResult(
        delivery: ClaimedDelivery,
        resolution: DeliveryResolution,
        finishedAt: Instant,
    ): Boolean {
        val state = resolution.toState(finishedAt)
        val parameters = MapSqlParameterSource()
            .addValue("id", delivery.id)
            .addValue("leaseToken", delivery.leaseToken)
            .addValue("status", state.status.name)
            .addValue("attemptCount", delivery.completedAttempts + 1)
            .addValue("nextAttemptAt", Timestamp.from(state.nextAttemptAt))
            .addValue("lastError", resolution.errorMessage?.take(MAX_ERROR_LENGTH))
            .addValue("updatedAt", Timestamp.from(finishedAt))

        val updated = jdbcTemplate.update(UPDATE_RESULT_SQL, parameters)
        if (updated != 1) {
            return false
        }

        jdbcTemplate.update(
            INSERT_ATTEMPT_SQL,
            MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("deliveryId", delivery.id)
                .addValue("attemptNumber", delivery.completedAttempts + 1)
                .addValue("outcome", state.outcome)
                .addValue("statusCode", resolution.statusCode)
                .addValue("errorMessage", resolution.errorMessage?.take(MAX_ERROR_LENGTH))
                .addValue("startedAt", Timestamp.from(delivery.claimedAt))
                .addValue("finishedAt", Timestamp.from(finishedAt)),
        )
        return true
    }

    private fun DeliveryResolution.toState(finishedAt: Instant): PersistedDeliveryState = when (this) {
        is DeliveryResolution.Succeeded -> PersistedDeliveryState(
            status = DeliveryStatus.SUCCEEDED,
            nextAttemptAt = finishedAt,
            outcome = "SUCCEEDED",
        )

        is DeliveryResolution.RetryAt -> PersistedDeliveryState(
            status = DeliveryStatus.RETRY_WAIT,
            nextAttemptAt = nextAttemptAt,
            outcome = "RETRY_SCHEDULED",
        )

        is DeliveryResolution.Failed -> PersistedDeliveryState(
            status = DeliveryStatus.FAILED,
            nextAttemptAt = finishedAt,
            outcome = "FAILED",
        )

        is DeliveryResolution.DeadLetter -> PersistedDeliveryState(
            status = DeliveryStatus.DEAD_LETTER,
            nextAttemptAt = finishedAt,
            outcome = "DEAD_LETTER",
        )
    }

    private data class PersistedDeliveryState(
        val status: DeliveryStatus,
        val nextAttemptAt: Instant,
        val outcome: String,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1000

        private val CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                FROM webhook_deliveries
                WHERE (
                    status IN ('PENDING', 'RETRY_WAIT')
                    AND next_attempt_at <= :now
                ) OR (
                    status = 'PROCESSING'
                    AND lease_until <= :now
                )
                ORDER BY next_attempt_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            ), claimed AS (
                UPDATE webhook_deliveries delivery
                SET status = 'PROCESSING',
                    lease_until = :leaseUntil,
                    lease_token = :leaseToken,
                    updated_at = :now
                FROM candidates
                WHERE delivery.id = candidates.id
                RETURNING delivery.*
            )
            SELECT claimed.id,
                   claimed.event_id,
                   event.event_type,
                   event.payload,
                   claimed.subscription_id,
                   subscription.endpoint_url,
                   subscription.signing_secret,
                   claimed.attempt_count,
                   claimed.lease_token
            FROM claimed
            JOIN webhook_events event ON event.id = claimed.event_id
            JOIN webhook_subscriptions subscription ON subscription.id = claimed.subscription_id
            ORDER BY claimed.next_attempt_at, claimed.id
        """.trimIndent()

        private val UPDATE_RESULT_SQL = """
            UPDATE webhook_deliveries
            SET status = :status,
                attempt_count = :attemptCount,
                next_attempt_at = :nextAttemptAt,
                lease_until = NULL,
                lease_token = NULL,
                last_error = :lastError,
                updated_at = :updatedAt
            WHERE id = :id
              AND status = 'PROCESSING'
              AND lease_token = :leaseToken
        """.trimIndent()

        private val INSERT_ATTEMPT_SQL = """
            INSERT INTO webhook_delivery_attempts (
                id, delivery_id, attempt_number, outcome, status_code,
                error_message, started_at, finished_at
            ) VALUES (
                :id, :deliveryId, :attemptNumber, :outcome, :statusCode,
                :errorMessage, :startedAt, :finishedAt
            )
        """.trimIndent()
    }
}
