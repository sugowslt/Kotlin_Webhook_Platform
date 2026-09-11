package com.sugowslt.hookrelay.subscription

import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class SubscriptionService(
    private val subscriptionStore: SubscriptionStore,
    private val webhookUrlPolicy: WebhookUrlPolicy,
    private val signingSecretGenerator: SigningSecretGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateSubscriptionCommand): CreatedSubscription {
        val name = command.name.trim()
        require(name.isNotEmpty()) { "Subscription name must not be blank" }
        require(name.length <= 100) { "Subscription name must not exceed 100 characters" }

        val endpointUrl = webhookUrlPolicy.validate(command.endpointUrl.trim()).uri.normalize().toASCIIString()
        require(endpointUrl.length <= 2048) { "Webhook URL must not exceed 2048 characters" }

        val eventTypes = EventTypePolicy.normalize(command.eventTypes)
        val now = Instant.now(clock)
        val subscription = WebhookSubscription(
            id = UUID.randomUUID(),
            name = name,
            endpointUrl = endpointUrl,
            signingSecret = signingSecretGenerator.generate(),
            eventTypes = eventTypes.toMutableSet(),
            active = true,
            createdAt = now,
            updatedAt = now,
        )

        return subscriptionStore.save(subscription).let {
            CreatedSubscription(
                id = it.id,
                name = it.name,
                endpointUrl = it.endpointUrl,
                eventTypes = it.eventTypes.toSet(),
                signingSecret = it.signingSecret,
                createdAt = it.createdAt,
            )
        }
    }
}

data class CreateSubscriptionCommand(
    val name: String,
    val endpointUrl: String,
    val eventTypes: Set<String>,
)

data class CreatedSubscription(
    val id: UUID,
    val name: String,
    val endpointUrl: String,
    val eventTypes: Set<String>,
    val signingSecret: String,
    val createdAt: Instant,
)

fun interface SigningSecretGenerator {
    fun generate(): String
}

@Service
class SecureSigningSecretGenerator : SigningSecretGenerator {
    private val secureRandom = SecureRandom()

    override fun generate(): String {
        val secret = ByteArray(32)
        secureRandom.nextBytes(secret)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }
}

object EventTypePolicy {
    private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun normalize(eventTypes: Set<String>): Set<String> {
        require(eventTypes.isNotEmpty()) { "At least one event type is required" }
        require(eventTypes.size <= 20) { "Event types must not exceed 20 entries" }

        return eventTypes.map { it.trim() }
            .onEach { require(it.matches(pattern)) { "Event type is invalid: $it" } }
            .toCollection(linkedSetOf())
    }

    fun normalize(eventType: String): String =
        eventType.trim().also { require(it.matches(pattern)) { "Event type is invalid: $it" } }
}
