package com.sugowslt.hookrelay.subscription

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "webhook_subscriptions")
class WebhookSubscription(
    @Id
    val id: UUID,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(name = "endpoint_url", nullable = false, length = 2048)
    val endpointUrl: String,

    @Column(name = "signing_secret", nullable = false, length = 128)
    val signingSecret: String,

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "webhook_subscription_event_types",
        joinColumns = [JoinColumn(name = "subscription_id")],
    )
    @Column(name = "event_type", nullable = false, length = 100)
    val eventTypes: MutableSet<String>,

    @Column(nullable = false)
    val active: Boolean,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
