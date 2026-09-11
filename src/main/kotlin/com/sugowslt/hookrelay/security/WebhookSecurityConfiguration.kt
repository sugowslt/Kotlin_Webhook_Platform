package com.sugowslt.hookrelay.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WebhookSecurityConfiguration {
    @Bean
    fun webhookUrlPolicy(): WebhookUrlPolicy = WebhookUrlPolicy()

    @Bean
    fun webhookSignatureService(): WebhookSignatureService = WebhookSignatureService()
}
