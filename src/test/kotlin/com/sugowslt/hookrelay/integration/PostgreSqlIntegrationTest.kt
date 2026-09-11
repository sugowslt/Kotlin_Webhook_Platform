package com.sugowslt.hookrelay.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.sugowslt.hookrelay.delivery.ClaimedDelivery
import com.sugowslt.hookrelay.delivery.DeliveryQueue
import com.sugowslt.hookrelay.delivery.DeliveryResolution
import com.sugowslt.hookrelay.delivery.DeliveryStore
import com.sugowslt.hookrelay.event.AcceptEventCommand
import com.sugowslt.hookrelay.event.EventIntakeService
import com.sugowslt.hookrelay.event.IdempotencyKeyConflictException
import com.sugowslt.hookrelay.event.WebhookEventStore
import com.sugowslt.hookrelay.subscription.SubscriptionStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(
    properties = [
        "hook-relay.worker.poll-interval-millis=3600000",
    ],
)
class PostgreSqlIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var eventIntakeService: EventIntakeService

    @Autowired
    private lateinit var eventStore: WebhookEventStore

    @Autowired
    private lateinit var subscriptionStore: SubscriptionStore

    @Autowired
    private lateinit var deliveryQueue: DeliveryQueue

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private val now = Instant.parse("2026-09-11T00:00:00Z")

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM webhook_delivery_attempts")
        jdbcTemplate.update("DELETE FROM webhook_deliveries")
        jdbcTemplate.update("DELETE FROM webhook_events")
        jdbcTemplate.update("DELETE FROM webhook_subscription_event_types")
        jdbcTemplate.update("DELETE FROM webhook_subscriptions")
    }

    @Test
    fun `Flyway가 필요한 테이블을 모두 생성한다`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )

        assertTrue(tables.contains("webhook_subscriptions"))
        assertTrue(tables.contains("webhook_subscription_event_types"))
        assertTrue(tables.contains("webhook_events"))
        assertTrue(tables.contains("webhook_deliveries"))
        assertTrue(tables.contains("webhook_delivery_attempts"))
    }

    @Test
    fun `같은 멱등키의 이벤트와 전달 작업은 한 번만 저장한다`() {
        insertSubscription("order.created")

        val first = eventIntakeService.accept(eventCommand("event-1", "{\"orderId\":1}"))
        val replay = eventIntakeService.accept(eventCommand("event-1", "{\"orderId\":1}"))

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.eventId, replay.eventId)
        assertEquals(1, replay.deliveryCount)
        assertEquals(1, count("webhook_events"))
        assertEquals(1, count("webhook_deliveries"))

        assertFailsWith<IdempotencyKeyConflictException> {
            eventIntakeService.accept(eventCommand("event-1", "{\"orderId\":2}"))
        }
        assertEquals(1, count("webhook_events"))
        assertEquals(1, count("webhook_deliveries"))
    }

    @Test
    fun `전달 작업 저장이 실패하면 이벤트도 함께 rollback된다`() {
        insertSubscription("order.created")
        val failingService = EventIntakeService(
            eventStore = eventStore,
            subscriptionStore = subscriptionStore,
            deliveryStore = object : DeliveryStore {
                override fun createPending(eventId: UUID, subscriptionIds: List<UUID>, now: Instant): Int {
                    throw IllegalStateException("delivery insert failed")
                }

                override fun countByEventId(eventId: UUID): Int = 0
            },
            objectMapper = objectMapper,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertFailsWith<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                failingService.accept(eventCommand("rollback-event", "{\"orderId\":1}"))
            }
        }

        assertEquals(0, count("webhook_events"))
        assertEquals(0, count("webhook_deliveries"))
    }

    @Test
    fun `lease가 끝난 작업은 새 token으로 다시 선점한다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("lease-event", "{\"orderId\":1}"))
        val claimAt = nextAttemptAt()

        val first = deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt).single()
        assertTrue(deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt.plusSeconds(5)).isEmpty())

        val reclaimed = deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt.plusSeconds(11)).single()
        assertEquals(first.id, reclaimed.id)
        assertNotEquals(first.leaseToken, reclaimed.leaseToken)

        assertFalse(
            deliveryQueue.recordResult(
                first,
                DeliveryResolution.Succeeded(200),
                claimAt.plusSeconds(12),
            ),
        )
        assertTrue(
            deliveryQueue.recordResult(
                reclaimed,
                DeliveryResolution.Succeeded(200),
                claimAt.plusSeconds(12),
            ),
        )
        assertEquals("SUCCEEDED", deliveryStatus(reclaimed.id))
        assertEquals(1, count("webhook_delivery_attempts"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `다른 트랜잭션이 잠근 작업은 기다리지 않고 건너뛴다`() {
        insertSubscription("order.created")
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("skip-locked-event", "{\"orderId\":1}"))
        val deliveryIds = jdbcTemplate.queryForList(
            "SELECT id FROM webhook_deliveries ORDER BY id",
            UUID::class.java,
        )
        val lockedId = deliveryIds.first()
        val claimAt = nextAttemptAt()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "SELECT id FROM webhook_deliveries WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, lockedId)
                statement.executeQuery().use { resultSet -> assertTrue(resultSet.next()) }
            }

            val claimed = deliveryQueue.claim(10, Duration.ofSeconds(10), claimAt)

            assertEquals(1, claimed.size)
            assertNotEquals(lockedId, claimed.single().id)
            connection.rollback()
        }
    }

    private fun insertSubscription(eventType: String): UUID {
        val subscriptionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO webhook_subscriptions (
                id, name, endpoint_url, signing_secret, active, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            subscriptionId,
            "receiver-$subscriptionId",
            "https://hooks.example.com/events",
            "signing-secret",
            true,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        jdbcTemplate.update(
            "INSERT INTO webhook_subscription_event_types (subscription_id, event_type) VALUES (?, ?)",
            subscriptionId,
            eventType,
        )
        return subscriptionId
    }

    private fun eventCommand(idempotencyKey: String, payload: String) = AcceptEventCommand(
        eventType = "order.created",
        idempotencyKey = idempotencyKey,
        payload = payload,
    )

    private fun count(table: String): Int {
        require(table in ALLOWED_TABLES)
        return checkNotNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))
    }

    private fun deliveryStatus(deliveryId: UUID): String = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT status FROM webhook_deliveries WHERE id = ?",
            String::class.java,
            deliveryId,
        ),
    )

    private fun nextAttemptAt(): Instant = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT MIN(next_attempt_at) FROM webhook_deliveries",
            Timestamp::class.java,
        ),
    ).toInstant()

    companion object {
        private val ALLOWED_TABLES = setOf(
            "webhook_subscriptions",
            "webhook_events",
            "webhook_deliveries",
            "webhook_delivery_attempts",
        )

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
