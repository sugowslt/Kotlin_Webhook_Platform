package com.sugowslt.hookrelay.delivery

import com.sugowslt.hookrelay.security.HostResolver
import com.sugowslt.hookrelay.security.WebhookSignatureService
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import java.io.IOException
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebhookDeliveryWorkerTest {
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private val signatureService = WebhookSignatureService()
    private val urlPolicy = WebhookUrlPolicy(
        hostResolver = HostResolver {
            listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        },
    )

    @Test
    fun `2xx 응답이면 전달을 성공 처리한다`() {
        val queue = RecordingDeliveryQueue(claimedDelivery())
        var sentRequest: WebhookHttpRequest? = null
        val worker = worker(queue) { request ->
            sentRequest = request
            WebhookHttpResponse(statusCode = 204, retryAfter = null)
        }

        assertEquals(1, worker.processBatch())

        val resolution = assertIs<DeliveryResolution.Succeeded>(queue.resolution)
        assertEquals(204, resolution.statusCode)
        assertEquals(
            signatureService.sign(
                secret = "signing-secret",
                timestampSeconds = now.epochSecond,
                deliveryId = queue.delivery.id.toString(),
                payload = "{\"orderId\":1}",
            ),
            sentRequest?.signature,
        )
    }

    @Test
    fun `503 응답이면 지수 backoff 뒤로 재시도를 예약한다`() {
        val queue = RecordingDeliveryQueue(claimedDelivery(completedAttempts = 0))
        val worker = worker(queue) {
            WebhookHttpResponse(statusCode = 503, retryAfter = null)
        }

        worker.processBatch()

        val resolution = assertIs<DeliveryResolution.RetryAt>(queue.resolution)
        assertEquals(now.plusSeconds(1), resolution.nextAttemptAt)
        assertEquals(503, resolution.statusCode)
    }

    @Test
    fun `일반 4xx 응답은 재시도하지 않는다`() {
        val queue = RecordingDeliveryQueue(claimedDelivery())
        val worker = worker(queue) {
            WebhookHttpResponse(statusCode = 400, retryAfter = null)
        }

        worker.processBatch()

        val resolution = assertIs<DeliveryResolution.Failed>(queue.resolution)
        assertEquals(400, resolution.statusCode)
    }

    @Test
    fun `마지막 네트워크 실패는 Dead Letter로 보낸다`() {
        val queue = RecordingDeliveryQueue(claimedDelivery(completedAttempts = 4))
        val worker = worker(queue) { throw IOException("connection timed out") }

        worker.processBatch()

        val resolution = assertIs<DeliveryResolution.DeadLetter>(queue.resolution)
        assertEquals("connection timed out; maximum attempts reached", resolution.errorMessage)
    }

    @Test
    fun `한 작업의 예기치 않은 오류가 다음 작업을 막지 않는다`() {
        val deliveries = listOf(claimedDelivery(), claimedDelivery())
        var recordedCount = 0
        val queue = object : DeliveryQueue {
            override fun claim(batchSize: Int, leaseDuration: Duration, now: Instant): List<ClaimedDelivery> =
                deliveries

            override fun recordResult(
                delivery: ClaimedDelivery,
                resolution: DeliveryResolution,
                finishedAt: Instant,
            ): Boolean {
                recordedCount += 1
                return true
            }
        }
        var requestCount = 0
        val worker = worker(queue) {
            requestCount += 1
            if (requestCount == 1) {
                throw IllegalStateException("unexpected failure")
            }
            WebhookHttpResponse(statusCode = 204, retryAfter = null)
        }

        assertEquals(2, worker.processBatch())
        assertEquals(1, recordedCount)
    }

    private fun worker(
        queue: DeliveryQueue,
        httpClient: WebhookHttpClient,
    ): WebhookDeliveryWorker = WebhookDeliveryWorker(
        deliveryQueue = queue,
        httpClient = httpClient,
        signatureService = signatureService,
        webhookUrlPolicy = urlPolicy,
        retryPolicy = RetryPolicy(random = { 0.5 }),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        batchSize = 20,
        leaseSeconds = 30,
        requestTimeoutSeconds = 5,
    )

    private fun claimedDelivery(completedAttempts: Int = 0): ClaimedDelivery = ClaimedDelivery(
        id = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        eventType = "order.created",
        payload = "{\"orderId\":1}",
        subscriptionId = UUID.randomUUID(),
        endpointUrl = "https://hooks.example.com/events",
        signingSecret = "signing-secret",
        completedAttempts = completedAttempts,
        leaseToken = UUID.randomUUID(),
        claimedAt = now,
    )

    private class RecordingDeliveryQueue(
        val delivery: ClaimedDelivery,
    ) : DeliveryQueue {
        var resolution: DeliveryResolution? = null

        override fun claim(batchSize: Int, leaseDuration: Duration, now: Instant): List<ClaimedDelivery> =
            listOf(delivery)

        override fun recordResult(
            delivery: ClaimedDelivery,
            resolution: DeliveryResolution,
            finishedAt: Instant,
        ): Boolean {
            this.resolution = resolution
            return true
        }
    }
}
