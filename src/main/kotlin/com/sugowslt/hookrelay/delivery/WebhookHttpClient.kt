package com.sugowslt.hookrelay.delivery

import com.sugowslt.hookrelay.security.WebhookSignatureService
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun interface WebhookHttpClient {
    fun send(request: WebhookHttpRequest): WebhookHttpResponse
}

data class WebhookHttpRequest(
    val endpointUrl: String,
    val payload: String,
    val signature: String,
    val timestampSeconds: Long,
    val deliveryId: String,
    val timeout: Duration,
)

data class WebhookHttpResponse(
    val statusCode: Int,
    val retryAfter: Duration?,
)

@Component
class JavaWebhookHttpClient : WebhookHttpClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun send(request: WebhookHttpRequest): WebhookHttpResponse {
        val httpRequest = HttpRequest.newBuilder(URI.create(request.endpointUrl))
            .timeout(request.timeout)
            .header("Content-Type", "application/json")
            .header(WebhookSignatureService.SIGNATURE_HEADER, request.signature)
            .header(WebhookSignatureService.TIMESTAMP_HEADER, request.timestampSeconds.toString())
            .header(WebhookSignatureService.DELIVERY_ID_HEADER, request.deliveryId)
            .POST(HttpRequest.BodyPublishers.ofString(request.payload, StandardCharsets.UTF_8))
            .build()
        val response = client.send(httpRequest, HttpResponse.BodyHandlers.discarding())
        return WebhookHttpResponse(
            statusCode = response.statusCode(),
            retryAfter = RetryAfterParser.parse(
                value = response.headers().firstValue("Retry-After").orElse(null),
                now = Instant.now(),
            ),
        )
    }
}

object RetryAfterParser {
    fun parse(value: String?, now: Instant): Duration? {
        if (value.isNullOrBlank()) {
            return null
        }

        value.trim().toLongOrNull()?.let { seconds ->
            return Duration.ofSeconds(seconds.coerceAtLeast(0))
        }
        return runCatching {
            val retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(now, retryAt).coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }
}
