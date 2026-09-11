package com.sugowslt.hookrelay.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.sugowslt.hookrelay.delivery.DeliveryStore
import com.sugowslt.hookrelay.subscription.SubscriptionStore
import com.sugowslt.hookrelay.subscription.WebhookSubscription
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventIntakeServiceTest {
    private val fixedInstant = Instant.parse("2026-09-11T00:00:00Z")
    private val eventStore = InMemoryWebhookEventStore()
    private val subscriptionIds = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val subscriptionStore = FixedSubscriptionStore(subscriptionIds)
    private val deliveryStore = RecordingDeliveryStore()
    private val service = EventIntakeService(
        eventStore = eventStore,
        subscriptionStore = subscriptionStore,
        deliveryStore = deliveryStore,
        objectMapper = ObjectMapper(),
        clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
    )

    @Test
    fun `새 이벤트를 저장하고 활성 구독 수만큼 전달 작업을 만든다`() {
        val accepted = service.accept(command(payload = "{\"orderId\":1}"))

        assertEquals(false, accepted.replayed)
        assertEquals(2, accepted.deliveryCount)
        assertEquals(fixedInstant, accepted.acceptedAt)
        assertEquals(subscriptionIds, deliveryStore.subscriptionIds)
        assertEquals(accepted.eventId, deliveryStore.eventId)
    }

    @Test
    fun `같은 멱등키와 요청을 다시 보내면 기존 이벤트를 반환한다`() {
        val first = service.accept(command(payload = "{\"orderId\":1}"))
        val second = service.accept(command(payload = "{\"orderId\":1}"))

        assertTrue(second.replayed)
        assertEquals(first.eventId, second.eventId)
        assertEquals(2, second.deliveryCount)
        assertEquals(1, deliveryStore.createdCount)
    }

    @Test
    fun `같은 멱등키로 다른 요청을 보내면 충돌로 처리한다`() {
        service.accept(command(payload = "{\"orderId\":1}"))

        val exception = assertFailsWith<IdempotencyKeyConflictException> {
            service.accept(command(payload = "{\"orderId\":2}"))
        }

        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.errorCode)
        assertEquals(1, deliveryStore.createdCount)
    }

    @Test
    fun `공백이 포함된 멱등키는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            service.accept(command(idempotencyKey = "event key"))
        }
    }

    @Test
    fun `JSON 형식이 아닌 본문은 저장하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            service.accept(command(payload = "not-json"))
        }

        assertEquals(0, eventStore.size)
        assertEquals(0, deliveryStore.createdCount)
    }

    private fun command(
        idempotencyKey: String = "event-20260911-1",
        payload: String = "{\"orderId\":1}",
    ) = AcceptEventCommand(
        eventType = "order.created",
        idempotencyKey = idempotencyKey,
        payload = payload,
    )

    private class InMemoryWebhookEventStore : WebhookEventStore {
        private val events = mutableMapOf<String, WebhookEvent>()
        val size: Int
            get() = events.size

        override fun insertIfAbsent(event: WebhookEvent): Boolean =
            events.putIfAbsent(event.idempotencyKey, event) == null

        override fun findByIdempotencyKey(idempotencyKey: String): WebhookEvent? = events[idempotencyKey]
    }

    private class FixedSubscriptionStore(
        private val subscriptionIds: List<UUID>,
    ) : SubscriptionStore {
        override fun save(subscription: WebhookSubscription): WebhookSubscription = subscription

        override fun findActiveIdsByEventType(eventType: String): List<UUID> = subscriptionIds
    }

    private class RecordingDeliveryStore : DeliveryStore {
        var eventId: UUID? = null
        var subscriptionIds: List<UUID> = emptyList()
        var createdCount: Int = 0

        override fun createPending(eventId: UUID, subscriptionIds: List<UUID>, now: Instant): Int {
            this.eventId = eventId
            this.subscriptionIds = subscriptionIds
            createdCount += 1
            return subscriptionIds.size
        }

        override fun countByEventId(eventId: UUID): Int = subscriptionIds.size
    }
}
