package com.sugowslt.hookrelay.subscription

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.util.UUID

interface SubscriptionStore {
    fun save(subscription: WebhookSubscription): WebhookSubscription

    fun findActiveIdsByEventType(@Param("eventType") eventType: String): List<UUID>
}

interface SpringDataWebhookSubscriptionRepository : JpaRepository<WebhookSubscription, UUID> {
    @Query(
        """
        select distinct subscription.id
        from WebhookSubscription subscription
        join subscription.eventTypes eventType
        where subscription.active = true and eventType = :eventType
        """,
    )
    fun findActiveIdsByEventType(eventType: String): List<UUID>
}

@Component
class JpaSubscriptionStore(
    private val repository: SpringDataWebhookSubscriptionRepository,
) : SubscriptionStore {
    override fun save(subscription: WebhookSubscription): WebhookSubscription = repository.save(subscription)

    override fun findActiveIdsByEventType(eventType: String): List<UUID> =
        repository.findActiveIdsByEventType(eventType)
}
