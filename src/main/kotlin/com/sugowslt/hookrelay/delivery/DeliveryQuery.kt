package com.sugowslt.hookrelay.delivery

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class DeliverySummary(
    val deliveryId: UUID,
    val eventId: UUID,
    val subscriptionId: UUID,
    val eventType: String,
    val status: DeliveryStatus,
    val completedAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseUntil: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeliveryAttempt(
    val attemptNumber: Int,
    val outcome: String,
    val statusCode: Int?,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant,
)

data class DeliveryPageCursor(
    val createdAt: Instant,
    val deliveryId: UUID,
    val status: DeliveryStatus?,
)

interface DeliveryQueryStore {
    fun findPage(status: DeliveryStatus?, cursor: DeliveryPageCursor?, limit: Int): List<DeliverySummary>

    fun findById(deliveryId: UUID): DeliverySummary?

    fun findAttempts(deliveryId: UUID): List<DeliveryAttempt>
}

@Repository
class JdbcDeliveryQueryStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DeliveryQueryStore {
    override fun findPage(
        status: DeliveryStatus?,
        cursor: DeliveryPageCursor?,
        limit: Int,
    ): List<DeliverySummary> {
        val conditions = mutableListOf<String>()
        val parameters = MapSqlParameterSource()
            .addValue("limit", limit)

        if (status != null) {
            conditions += "d.status = :status"
            parameters.addValue("status", status.name)
        }
        if (cursor != null) {
            conditions += """
                (
                    d.created_at < :cursorCreatedAt
                    OR (d.created_at = :cursorCreatedAt AND d.id < :cursorDeliveryId)
                )
            """.trimIndent()
            parameters
                .addValue("cursorCreatedAt", Timestamp.from(cursor.createdAt))
                .addValue("cursorDeliveryId", cursor.deliveryId)
        }

        val whereClause = conditions.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "WHERE ", separator = "\n  AND ")
            .orEmpty()
        val query = """
            $SUMMARY_SELECT
            $whereClause
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit
        """.trimIndent()
        return jdbcTemplate.query(query, parameters, ::mapSummary)
    }

    override fun findById(deliveryId: UUID): DeliverySummary? = jdbcTemplate.query(
        """
            $SUMMARY_SELECT
            WHERE d.id = :deliveryId
        """.trimIndent(),
        MapSqlParameterSource("deliveryId", deliveryId),
        ::mapSummary,
    ).singleOrNull()

    override fun findAttempts(deliveryId: UUID): List<DeliveryAttempt> = jdbcTemplate.query(
        """
            SELECT attempt_number, outcome, status_code, error_message, started_at, finished_at
            FROM webhook_delivery_attempts
            WHERE delivery_id = :deliveryId
            ORDER BY attempt_number
        """.trimIndent(),
        MapSqlParameterSource("deliveryId", deliveryId),
    ) { resultSet, _ ->
        DeliveryAttempt(
            attemptNumber = resultSet.getInt("attempt_number"),
            outcome = resultSet.getString("outcome"),
            statusCode = resultSet.nullableInt("status_code"),
            errorMessage = resultSet.getString("error_message"),
            startedAt = resultSet.getTimestamp("started_at").toInstant(),
            finishedAt = resultSet.getTimestamp("finished_at").toInstant(),
        )
    }

    private fun mapSummary(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) = DeliverySummary(
        deliveryId = resultSet.getObject("delivery_id", UUID::class.java),
        eventId = resultSet.getObject("event_id", UUID::class.java),
        subscriptionId = resultSet.getObject("subscription_id", UUID::class.java),
        eventType = resultSet.getString("event_type"),
        status = DeliveryStatus.valueOf(resultSet.getString("status")),
        completedAttempts = resultSet.getInt("attempt_count"),
        nextAttemptAt = resultSet.getTimestamp("next_attempt_at").toInstant(),
        leaseUntil = resultSet.getTimestamp("lease_until")?.toInstant(),
        lastError = resultSet.getString("last_error"),
        createdAt = resultSet.getTimestamp("created_at").toInstant(),
        updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.nullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    companion object {
        private val SUMMARY_SELECT = """
            SELECT d.id AS delivery_id,
                   d.event_id,
                   d.subscription_id,
                   e.event_type,
                   d.status,
                   d.attempt_count,
                   d.next_attempt_at,
                   d.lease_until,
                   d.last_error,
                   d.created_at,
                   d.updated_at
            FROM webhook_deliveries d
            JOIN webhook_events e ON e.id = d.event_id
        """.trimIndent()
    }
}

@Service
class DeliveryQueryService(
    private val deliveryQueryStore: DeliveryQueryStore,
) {
    @Transactional(readOnly = true)
    fun list(statusValue: String?, limitValue: String, cursorValue: String?): DeliveryPageResponse {
        val status = statusValue?.let(::parseStatus)
        val limit = limitValue.toIntOrNull()
        require(limit != null && limit in 1..MAX_PAGE_SIZE) {
            "limit must be an integer between 1 and $MAX_PAGE_SIZE"
        }
        val cursor = cursorValue?.let(DeliveryCursorCodec::decode)
        require(cursor == null || cursor.status == status) {
            "cursor does not match the status filter"
        }

        val rows = deliveryQueryStore.findPage(status, cursor, limit + 1)
        val page = rows.take(limit)
        return DeliveryPageResponse(
            items = page.map(DeliverySummaryResponse::from),
            nextCursor = if (rows.size > limit) {
                page.last().let { DeliveryCursorCodec.encode(it.createdAt, it.deliveryId, status) }
            } else {
                null
            },
        )
    }

    @Transactional(readOnly = true)
    fun get(deliveryId: UUID): DeliveryDetailResponse {
        val delivery = deliveryQueryStore.findById(deliveryId)
            ?: throw DeliveryNotFoundException(deliveryId)
        return DeliveryDetailResponse.from(
            delivery = delivery,
            attempts = deliveryQueryStore.findAttempts(deliveryId),
        )
    }

    private fun parseStatus(value: String): DeliveryStatus {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return runCatching { DeliveryStatus.valueOf(normalized) }
            .getOrElse {
                throw IllegalArgumentException(
                    "status must be one of ${DeliveryStatus.entries.joinToString { status -> status.name }}",
                )
            }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = "30"
        const val MAX_PAGE_SIZE = 100
    }
}

object DeliveryCursorCodec {
    private const val VERSION = "v1"
    private const val ALL_STATUSES = "*"
    private const val MAX_CURSOR_LENGTH = 512

    fun encode(createdAt: Instant, deliveryId: UUID, status: DeliveryStatus?): String {
        val raw = "$VERSION|$createdAt|$deliveryId|${status?.name ?: ALL_STATUSES}"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(value: String): DeliveryPageCursor {
        require(value.isNotBlank() && value.length <= MAX_CURSOR_LENGTH) {
            "cursor must be a valid delivery page cursor"
        }
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            val parts = decoded.split('|')
            require(parts.size == 4 && parts[0] == VERSION)
            DeliveryPageCursor(
                createdAt = Instant.parse(parts[1]),
                deliveryId = UUID.fromString(parts[2]),
                status = parts[3].takeUnless { it == ALL_STATUSES }?.let(DeliveryStatus::valueOf),
            )
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("cursor must be a valid delivery page cursor", exception)
        }
    }
}

data class DeliverySummaryResponse(
    val deliveryId: UUID,
    val eventId: UUID,
    val subscriptionId: UUID,
    val eventType: String,
    val status: DeliveryStatus,
    val completedAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseUntil: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(delivery: DeliverySummary) = DeliverySummaryResponse(
            deliveryId = delivery.deliveryId,
            eventId = delivery.eventId,
            subscriptionId = delivery.subscriptionId,
            eventType = delivery.eventType,
            status = delivery.status,
            completedAttempts = delivery.completedAttempts,
            nextAttemptAt = delivery.nextAttemptAt,
            leaseUntil = delivery.leaseUntil,
            lastError = delivery.lastError,
            createdAt = delivery.createdAt,
            updatedAt = delivery.updatedAt,
        )
    }
}

data class DeliveryAttemptResponse(
    val attemptNumber: Int,
    val outcome: String,
    val statusCode: Int?,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    companion object {
        fun from(attempt: DeliveryAttempt) = DeliveryAttemptResponse(
            attemptNumber = attempt.attemptNumber,
            outcome = attempt.outcome,
            statusCode = attempt.statusCode,
            errorMessage = attempt.errorMessage,
            startedAt = attempt.startedAt,
            finishedAt = attempt.finishedAt,
        )
    }
}

data class DeliveryPageResponse(
    val items: List<DeliverySummaryResponse>,
    val nextCursor: String?,
)

data class DeliveryDetailResponse(
    val deliveryId: UUID,
    val eventId: UUID,
    val subscriptionId: UUID,
    val eventType: String,
    val status: DeliveryStatus,
    val completedAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseUntil: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val attempts: List<DeliveryAttemptResponse>,
) {
    companion object {
        fun from(delivery: DeliverySummary, attempts: List<DeliveryAttempt>) = DeliveryDetailResponse(
            deliveryId = delivery.deliveryId,
            eventId = delivery.eventId,
            subscriptionId = delivery.subscriptionId,
            eventType = delivery.eventType,
            status = delivery.status,
            completedAttempts = delivery.completedAttempts,
            nextAttemptAt = delivery.nextAttemptAt,
            leaseUntil = delivery.leaseUntil,
            lastError = delivery.lastError,
            createdAt = delivery.createdAt,
            updatedAt = delivery.updatedAt,
            attempts = attempts.map(DeliveryAttemptResponse::from),
        )
    }
}

@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryQueryController(
    private val deliveryQueryService: DeliveryQueryService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = DeliveryQueryService.DEFAULT_PAGE_SIZE) limit: String,
        @RequestParam(required = false) cursor: String?,
    ): DeliveryPageResponse = deliveryQueryService.list(status, limit, cursor)

    @GetMapping("/{deliveryId}")
    fun get(
        @PathVariable deliveryId: UUID,
    ): DeliveryDetailResponse = deliveryQueryService.get(deliveryId)
}
