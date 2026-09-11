package com.sugowslt.hookrelay.delivery

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.sugowslt.hookrelay.security.HostResolver
import com.sugowslt.hookrelay.security.WebhookSignatureService
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebhookHttpIntegrationTest {
    private lateinit var wireMock: WireMockServer
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private val signatureService = WebhookSignatureService()

    @BeforeEach
    fun startServer() {
        wireMock = WireMockServer(options().dynamicPort())
        wireMock.start()
    }

    @AfterEach
    fun stopServer() {
        if (::wireMock.isInitialized) {
            wireMock.stop()
        }
    }

    @Test
    fun `실제 HTTP 요청에 본문과 HMAC 헤더를 담아 전송한다`() {
        wireMock.stubFor(post(urlEqualTo("/events")).willReturn(aResponse().withStatus(204)))
        val delivery = claimedDelivery()
        val queue = RecordingQueue(delivery)

        worker(queue).processBatch()

        assertIs<DeliveryResolution.Succeeded>(queue.resolution)
        val request = wireMock.allServeEvents.single().request
        assertEquals(delivery.payload, request.bodyAsString)
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals(now.epochSecond.toString(), request.getHeader(WebhookSignatureService.TIMESTAMP_HEADER))
        assertEquals(delivery.id.toString(), request.getHeader(WebhookSignatureService.DELIVERY_ID_HEADER))
        assertEquals(
            signatureService.sign(
                secret = delivery.signingSecret,
                timestampSeconds = now.epochSecond,
                deliveryId = delivery.id.toString(),
                payload = delivery.payload,
            ),
            request.getHeader(WebhookSignatureService.SIGNATURE_HEADER),
        )
    }

    @Test
    fun `503의 Retry-After를 다음 시도 시각에 반영한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/events")).willReturn(
                aResponse()
                    .withStatus(503)
                    .withHeader("Retry-After", "7"),
            ),
        )
        val queue = RecordingQueue(claimedDelivery())

        worker(queue).processBatch()

        val resolution = assertIs<DeliveryResolution.RetryAt>(queue.resolution)
        assertEquals(503, resolution.statusCode)
        assertEquals(now.plusSeconds(7), resolution.nextAttemptAt)
    }

    @Test
    fun `redirect 응답을 따라가지 않고 영구 실패로 처리한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/events")).willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", "/redirected"),
            ),
        )
        val queue = RecordingQueue(claimedDelivery())

        worker(queue).processBatch()

        val resolution = assertIs<DeliveryResolution.Failed>(queue.resolution)
        assertEquals(302, resolution.statusCode)
        assertEquals(1, wireMock.allServeEvents.size)
        assertEquals("/events", wireMock.allServeEvents.single().request.url)
    }

    private fun worker(queue: DeliveryQueue): WebhookDeliveryWorker = WebhookDeliveryWorker(
        deliveryQueue = queue,
        httpClient = JavaWebhookHttpClient(),
        signatureService = signatureService,
        webhookUrlPolicy = WebhookUrlPolicy(
            allowedSchemes = setOf("http"),
            hostResolver = HostResolver {
                listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            },
        ),
        retryPolicy = RetryPolicy(random = { 0.5 }),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        batchSize = 1,
        leaseSeconds = 30,
        requestTimeoutSeconds = 5,
    )

    private fun claimedDelivery() = ClaimedDelivery(
        id = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        eventType = "order.created",
        payload = "{\"orderId\":1}",
        subscriptionId = UUID.randomUUID(),
        endpointUrl = "http://127.0.0.1:${wireMock.port()}/events",
        signingSecret = "signing-secret",
        completedAttempts = 0,
        leaseToken = UUID.randomUUID(),
        claimedAt = now,
    )

    private class RecordingQueue(
        private val delivery: ClaimedDelivery,
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
