package com.sugowslt.hookrelay.delivery

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

interface DeliveryStore {
    fun createPending(eventId: UUID, subscriptionIds: List<UUID>, now: Instant): Int

    fun countByEventId(eventId: UUID): Int
}

interface SpringDataWebhookDeliveryRepository : JpaRepository<WebhookDelivery, UUID> {
    fun countByEventId(eventId: UUID): Long
}

@Component
class JpaDeliveryStore(
    private val repository: SpringDataWebhookDeliveryRepository,
) : DeliveryStore {
    override fun createPending(eventId: UUID, subscriptionIds: List<UUID>, now: Instant): Int {
        if (subscriptionIds.isEmpty()) {
            return 0
        }

        val deliveries = subscriptionIds.map { subscriptionId ->
            WebhookDelivery(
                id = UUID.randomUUID(),
                eventId = eventId,
                subscriptionId = subscriptionId,
                status = DeliveryStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = now,
                leaseUntil = null,
                lastError = null,
                createdAt = now,
                updatedAt = now,
            )
        }
        repository.saveAll(deliveries)
        return deliveries.size
    }

    override fun countByEventId(eventId: UUID): Int = repository.countByEventId(eventId).toInt()
}
