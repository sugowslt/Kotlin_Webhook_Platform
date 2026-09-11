package com.sugowslt.hookrelay.subscription

import com.sugowslt.hookrelay.security.HostResolver
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SubscriptionServiceTest {
    private val fixedInstant = Instant.parse("2026-09-11T00:00:00Z")
    private val store = InMemorySubscriptionStore()
    private val service = SubscriptionService(
        subscriptionStore = store,
        webhookUrlPolicy = WebhookUrlPolicy(
            hostResolver = HostResolver {
                listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            },
        ),
        signingSecretGenerator = SigningSecretGenerator { "fixed-signing-secret" },
        clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
    )

    @Test
    fun `구독을 등록하면 URL과 이벤트 유형을 정규화하고 비밀값을 반환한다`() {
        val created = service.create(
            CreateSubscriptionCommand(
                name = " order receiver ",
                endpointUrl = "https://hooks.example.com/a/../events",
                eventTypes = linkedSetOf(" order.created ", "order.cancelled"),
            ),
        )

        assertEquals("order receiver", created.name)
        assertEquals("https://hooks.example.com/events", created.endpointUrl)
        assertEquals(setOf("order.created", "order.cancelled"), created.eventTypes)
        assertEquals("fixed-signing-secret", created.signingSecret)
        assertEquals(fixedInstant, created.createdAt)
        assertNotNull(store.saved)
    }

    @Test
    fun `허용하지 않는 이벤트 유형은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            service.create(
                CreateSubscriptionCommand(
                    name = "receiver",
                    endpointUrl = "https://hooks.example.com/events",
                    eventTypes = setOf("order created"),
                ),
            )
        }
    }

    private class InMemorySubscriptionStore : SubscriptionStore {
        var saved: WebhookSubscription? = null

        override fun save(subscription: WebhookSubscription): WebhookSubscription {
            saved = subscription
            return subscription
        }

        override fun findActiveIdsByEventType(eventType: String): List<UUID> = emptyList()
    }
}
