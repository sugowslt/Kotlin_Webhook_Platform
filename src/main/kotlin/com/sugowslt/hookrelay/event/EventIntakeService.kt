package com.sugowslt.hookrelay.event

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.sugowslt.hookrelay.delivery.DeliveryStore
import com.sugowslt.hookrelay.subscription.EventTypePolicy
import com.sugowslt.hookrelay.subscription.SubscriptionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class EventIntakeService(
    private val eventStore: WebhookEventStore,
    private val subscriptionStore: SubscriptionStore,
    private val deliveryStore: DeliveryStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun accept(command: AcceptEventCommand): AcceptedEvent {
        val eventType = EventTypePolicy.normalize(command.eventType)
        val idempotencyKey = IdempotencyKeyPolicy.validate(command.idempotencyKey)
        require(command.payload.isNotBlank()) { "Payload must not be blank" }
        require(command.payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Payload must not exceed $MAX_PAYLOAD_BYTES bytes"
        }
        validateJson(command.payload)

        val fingerprint = RequestFingerprint.calculate(eventType, command.payload)
        val now = Instant.now(clock)
        val event = WebhookEvent(
            id = UUID.randomUUID(),
            eventType = eventType,
            payload = command.payload,
            idempotencyKey = idempotencyKey,
            requestFingerprint = fingerprint,
            createdAt = now,
        )

        if (!eventStore.insertIfAbsent(event)) {
            val existing = checkNotNull(eventStore.findByIdempotencyKey(idempotencyKey)) {
                "Existing event could not be loaded after idempotency conflict"
            }
            if (existing.requestFingerprint != fingerprint) {
                throw IdempotencyKeyConflictException()
            }
            return AcceptedEvent(
                eventId = existing.id,
                deliveryCount = deliveryStore.countByEventId(existing.id),
                replayed = true,
                acceptedAt = existing.createdAt,
            )
        }

        val subscriptionIds = subscriptionStore.findActiveIdsByEventType(eventType)
        val deliveryCount = deliveryStore.createPending(event.id, subscriptionIds, now)
        return AcceptedEvent(
            eventId = event.id,
            deliveryCount = deliveryCount,
            replayed = false,
            acceptedAt = event.createdAt,
        )
    }

    private fun validateJson(payload: String) {
        try {
            objectMapper.readTree(payload)
        } catch (exception: JsonProcessingException) {
            throw IllegalArgumentException("Payload must be valid JSON", exception)
        }
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}

data class AcceptEventCommand(
    val eventType: String,
    val idempotencyKey: String,
    val payload: String,
)

data class AcceptedEvent(
    val eventId: UUID,
    val deliveryCount: Int,
    val replayed: Boolean,
    val acceptedAt: Instant,
)

object IdempotencyKeyPolicy {
    private val pattern = Regex("^[\\x21-\\x7E]{1,200}$")

    fun validate(idempotencyKey: String): String = idempotencyKey.also {
        require(it.matches(pattern)) { "Idempotency-Key must be 1 to 200 visible ASCII characters" }
    }
}

object RequestFingerprint {
    fun calculate(eventType: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$eventType\n$payload".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

class IdempotencyKeyConflictException(
    val errorCode: String = "IDEMPOTENCY_KEY_REUSED",
    override val message: String = "Idempotency-Key was already used with a different request",
) : IllegalStateException(message)
