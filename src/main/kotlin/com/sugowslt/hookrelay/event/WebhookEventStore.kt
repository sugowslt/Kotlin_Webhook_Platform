package com.sugowslt.hookrelay.event

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

interface WebhookEventStore {
    fun insertIfAbsent(event: WebhookEvent): Boolean

    fun findByIdempotencyKey(idempotencyKey: String): WebhookEvent?
}

@Repository
class JdbcWebhookEventStore(
    private val jdbcTemplate: JdbcTemplate,
) : WebhookEventStore {
    override fun insertIfAbsent(event: WebhookEvent): Boolean = jdbcTemplate.update(
        """
        INSERT INTO webhook_events (
            id, event_type, payload, idempotency_key, request_fingerprint, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (idempotency_key) DO NOTHING
        """.trimIndent(),
        event.id,
        event.eventType,
        event.payload,
        event.idempotencyKey,
        event.requestFingerprint,
        Timestamp.from(event.createdAt),
    ) == 1

    override fun findByIdempotencyKey(idempotencyKey: String): WebhookEvent? = jdbcTemplate.query(
        """
        SELECT id, event_type, payload, idempotency_key, request_fingerprint, created_at
        FROM webhook_events
        WHERE idempotency_key = ?
        """.trimIndent(),
        { resultSet, _ ->
            WebhookEvent(
                id = resultSet.getObject("id", java.util.UUID::class.java),
                eventType = resultSet.getString("event_type"),
                payload = resultSet.getString("payload"),
                idempotencyKey = resultSet.getString("idempotency_key"),
                requestFingerprint = resultSet.getString("request_fingerprint"),
                createdAt = resultSet.getTimestamp("created_at").toInstant(),
            )
        },
        idempotencyKey,
    ).firstOrNull()
}
