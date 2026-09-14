package com.sugowslt.hookrelay.delivery

import com.sugowslt.hookrelay.security.WebhookSignatureService
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Clock
import kotlin.test.Test

class WebhookDeliveryWorkerConditionTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(WorkerTestConfiguration::class.java)

    @Test
    fun `기본 설정에서는 Worker를 등록한다`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(WebhookDeliveryWorker::class.java)
        }
    }

    @Test
    fun `Worker를 비활성화하면 빈을 등록하지 않는다`() {
        contextRunner
            .withPropertyValues("hook-relay.worker.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(WebhookDeliveryWorker::class.java)
            }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@Import(WebhookDeliveryWorker::class)
private class WorkerTestConfiguration {
    @Bean
    fun deliveryQueue(): DeliveryQueue = mock(DeliveryQueue::class.java)

    @Bean
    fun webhookHttpClient(): WebhookHttpClient = WebhookHttpClient {
        error("HTTP client must not be called while creating the context")
    }

    @Bean
    fun webhookSignatureService(): WebhookSignatureService = WebhookSignatureService()

    @Bean
    fun webhookUrlPolicy(): WebhookUrlPolicy = WebhookUrlPolicy()

    @Bean
    fun retryPolicy(): RetryPolicy = RetryPolicy()

    @Bean
    fun deliveryMetrics(): DeliveryMetrics = DeliveryMetrics(SimpleMeterRegistry())

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
