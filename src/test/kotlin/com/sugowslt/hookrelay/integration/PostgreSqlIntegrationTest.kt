package com.sugowslt.hookrelay.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.sugowslt.hookrelay.common.TraceIdFilter
import com.sugowslt.hookrelay.delivery.ClaimedDelivery
import com.sugowslt.hookrelay.delivery.DeliveryMetrics
import com.sugowslt.hookrelay.delivery.DeliveryMetricOutcome
import com.sugowslt.hookrelay.delivery.DeliveryQueue
import com.sugowslt.hookrelay.delivery.DeliveryRedeliveryResult
import com.sugowslt.hookrelay.delivery.DeliveryRedeliveryStore
import com.sugowslt.hookrelay.delivery.DeliveryResolution
import com.sugowslt.hookrelay.delivery.DeliveryStore
import com.sugowslt.hookrelay.delivery.DeliveryStatus
import com.sugowslt.hookrelay.delivery.RetryPolicy
import com.sugowslt.hookrelay.delivery.WebhookDeliveryWorker
import com.sugowslt.hookrelay.delivery.WebhookHttpClient
import com.sugowslt.hookrelay.delivery.WebhookHttpResponse
import com.sugowslt.hookrelay.event.AcceptEventCommand
import com.sugowslt.hookrelay.event.EventIntakeService
import com.sugowslt.hookrelay.event.IdempotencyKeyConflictException
import com.sugowslt.hookrelay.event.WebhookEventStore
import com.sugowslt.hookrelay.security.HostResolver
import com.sugowslt.hookrelay.security.WebhookSignatureService
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import com.sugowslt.hookrelay.subscription.SubscriptionStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetAddress
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "hook-relay.worker.poll-interval-millis=3600000",
        "hook-relay.security.operator-token=test-operator-token-that-is-not-secret",
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
    private lateinit var deliveryRedeliveryStore: DeliveryRedeliveryStore

    @Autowired
    private lateinit var deliveryMetrics: DeliveryMetrics

    @Autowired
    private lateinit var mockMvc: MockMvc

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
    fun `Prometheus endpoint가 전달 지표를 scrape 형식으로 노출한다`() {
        deliveryMetrics.recordClaimed(2)
        val sample = deliveryMetrics.startProcessing()
        deliveryMetrics.recordProcessing(sample, DeliveryMetricOutcome.SUCCEEDED)

        val response = mockMvc.perform(get("/actuator/prometheus"))
            .andReturn()
            .response
        val body = response.contentAsString

        assertEquals(200, response.status)
        assertTrue(body.contains("hookrelay_delivery_claimed_total"))
        assertTrue(body.contains("hookrelay_delivery_processing_seconds_count{outcome=\"succeeded\"}"))
    }

    @Test
    fun `운영자 토큰이 없으면 수동 재전송을 거부한다`() {
        val deliveryId = UUID.randomUUID()
        val traceId = "0123456789abcdef0123456789abcdef"

        val response = mockMvc.perform(
            post("/api/v1/deliveries/$deliveryId/redeliveries")
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId),
        )
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(401, response.status)
        assertEquals("Bearer realm=\"hook-relay\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        assertEquals(traceId, response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
        assertEquals("OPERATOR_AUTHENTICATION_REQUIRED", body["code"].asText())
        assertEquals("/api/v1/deliveries/$deliveryId/redeliveries", body["path"].asText())
        assertEquals(traceId, body["traceId"].asText())
    }

    @Test
    fun `잘못된 운영자 토큰이면 수동 재전송을 거부한다`() {
        val deliveryId = UUID.randomUUID()

        val response = mockMvc.perform(
            post("/api/v1/deliveries/$deliveryId/redeliveries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-operator-token-that-is-invalid"),
        )
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(401, response.status)
        assertEquals("OPERATOR_AUTHENTICATION_REQUIRED", body["code"].asText())
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `두 동시 선점 요청은 같은 작업을 중복 반환하지 않는다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("concurrent-event", "{\"orderId\":1}"))
        val claimAt = nextAttemptAt()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = (1..2).map {
                executor.submit<List<ClaimedDelivery>> {
                    ready.countDown()
                    start.await()
                    deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val claimed = futures.flatMap { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, claimed.size)
            assertEquals(1, claimed.map(ClaimedDelivery::id).distinct().size)
            assertEquals("PROCESSING", deliveryStatus(claimed.single().id))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `첫 Worker가 전달 중인 작업을 두 번째 Worker가 다시 처리하지 않는다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("worker-concurrent-event", "{\"orderId\":1}"))
        val claimAt = nextAttemptAt()
        val deliveryId = singleDeliveryId()
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val httpClient = WebhookHttpClient {
            requestCount.incrementAndGet()
            requestStarted.countDown()
            assertTrue(releaseResponse.await(5, TimeUnit.SECONDS))
            WebhookHttpResponse(statusCode = 204, retryAfter = null)
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<Int> { worker(httpClient, claimAt).processBatch() }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

            val second = executor.submit<Int> { worker(httpClient, claimAt).processBatch() }
            assertEquals(0, second.get(5, TimeUnit.SECONDS))

            releaseResponse.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS))
            assertEquals(1, requestCount.get())
            assertEquals("SUCCEEDED", deliveryStatus(deliveryId))
            assertEquals(1, count("webhook_delivery_attempts"))
        } finally {
            releaseResponse.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Dead Letter 전달을 수동 요청으로 다시 대기열에 넣는다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("redelivery-event", "{\"orderId\":1}"))
        val deliveryId = singleDeliveryId()
        val claimAt = nextAttemptAt()
        val claimed = deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt).single()
        assertTrue(
            deliveryQueue.recordResult(
                claimed,
                DeliveryResolution.DeadLetter(503, "HTTP 503; maximum attempts reached"),
                claimAt.plusSeconds(1),
            ),
        )

        val response = mockMvc.perform(
            post("/api/v1/deliveries/$deliveryId/redeliveries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $OPERATOR_TOKEN"),
        )
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(202, response.status)
        assertEquals(deliveryId.toString(), body["deliveryId"].asText())
        assertEquals("PENDING", body["status"].asText())
        assertEquals(1, body["completedAttempts"].asInt())
        assertEquals("PENDING", deliveryStatus(deliveryId))
        assertEquals(1, deliveryAttemptCount(deliveryId))
        assertEquals(1, count("webhook_delivery_attempts"))
        assertEquals(null, deliveryLastError(deliveryId))
    }

    @Test
    fun `완료된 전달의 수동 재전송은 충돌로 거부한다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("succeeded-redelivery-event", "{\"orderId\":1}"))
        val deliveryId = singleDeliveryId()
        val claimAt = nextAttemptAt()
        val claimed = deliveryQueue.claim(1, Duration.ofSeconds(10), claimAt).single()
        assertTrue(
            deliveryQueue.recordResult(
                claimed,
                DeliveryResolution.Succeeded(204),
                claimAt.plusSeconds(1),
            ),
        )

        val response = mockMvc.perform(
            post("/api/v1/deliveries/$deliveryId/redeliveries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $OPERATOR_TOKEN"),
        )
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(409, response.status)
        assertEquals("DELIVERY_NOT_REDELIVERABLE", body["code"].asText())
        assertEquals("SUCCEEDED", deliveryStatus(deliveryId))
        assertEquals(1, deliveryAttemptCount(deliveryId))
    }

    @Test
    fun `없는 전달의 수동 재전송은 찾을 수 없음으로 응답한다`() {
        val deliveryId = UUID.randomUUID()

        val response = mockMvc.perform(
            post("/api/v1/deliveries/$deliveryId/redeliveries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $OPERATOR_TOKEN"),
        )
            .andReturn()
            .response
        val body = objectMapper.readTree(response.contentAsString)

        assertEquals(404, response.status)
        assertEquals("DELIVERY_NOT_FOUND", body["code"].asText())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `동시 수동 재전송 요청은 한 건만 대기열에 넣는다`() {
        insertSubscription("order.created")
        eventIntakeService.accept(eventCommand("concurrent-redelivery-event", "{\"orderId\":1}"))
        val deliveryId = singleDeliveryId()
        val requestedAt = nextAttemptAt().plusSeconds(1)
        jdbcTemplate.update(
            "UPDATE webhook_deliveries SET status = 'DEAD_LETTER', last_error = 'HTTP 503' WHERE id = ?",
            deliveryId,
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = (1..2).map {
                executor.submit<DeliveryRedeliveryResult> {
                    ready.countDown()
                    start.await()
                    deliveryRedeliveryStore.requeue(deliveryId, requestedAt)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is DeliveryRedeliveryResult.Requeued })
            assertEquals(1, results.count { it == DeliveryRedeliveryResult.NotRedeliverable(DeliveryStatus.PENDING) })
            assertEquals("PENDING", deliveryStatus(deliveryId))
        } finally {
            start.countDown()
            executor.shutdownNow()
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

    private fun singleDeliveryId(): UUID = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT id FROM webhook_deliveries",
            UUID::class.java,
        ),
    )

    private fun deliveryAttemptCount(deliveryId: UUID): Int = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT attempt_count FROM webhook_deliveries WHERE id = ?",
            Int::class.java,
            deliveryId,
        ),
    )

    private fun deliveryLastError(deliveryId: UUID): String? = jdbcTemplate.queryForObject(
        "SELECT last_error FROM webhook_deliveries WHERE id = ?",
        String::class.java,
        deliveryId,
    )

    private fun nextAttemptAt(): Instant = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT MIN(next_attempt_at) FROM webhook_deliveries",
            Timestamp::class.java,
        ),
    ).toInstant()

    private fun worker(httpClient: WebhookHttpClient, instant: Instant): WebhookDeliveryWorker =
        WebhookDeliveryWorker(
            deliveryQueue = deliveryQueue,
            httpClient = httpClient,
            signatureService = WebhookSignatureService(),
            webhookUrlPolicy = WebhookUrlPolicy(
                hostResolver = HostResolver {
                    listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
                },
            ),
            retryPolicy = RetryPolicy(random = { 0.5 }),
            deliveryMetrics = DeliveryMetrics(SimpleMeterRegistry()),
            clock = Clock.fixed(instant, ZoneOffset.UTC),
            batchSize = 1,
            leaseSeconds = 30,
            requestTimeoutSeconds = 5,
        )

    companion object {
        private const val OPERATOR_TOKEN = "test-operator-token-that-is-not-secret"
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
