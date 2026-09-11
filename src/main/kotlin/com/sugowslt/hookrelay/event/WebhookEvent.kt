package com.sugowslt.hookrelay.event

import java.time.Instant
import java.util.UUID

data class WebhookEvent(
    val id: UUID,
    val eventType: String,
    val payload: String,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val createdAt: Instant,
)
