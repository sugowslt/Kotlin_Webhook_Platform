package com.sugowslt.hookrelay.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "webhook_deliveries")
class WebhookDelivery(
    @Id
    val id: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: UUID,

    @Column(name = "subscription_id", nullable = false)
    val subscriptionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: DeliveryStatus,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,

    @Column(name = "lease_until")
    var leaseUntil: Instant?,

    @Column(name = "last_error", length = 1000)
    var lastError: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    RETRY_WAIT,
    FAILED,
    DEAD_LETTER,
}
