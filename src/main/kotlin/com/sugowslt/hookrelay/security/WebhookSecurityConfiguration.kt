package com.sugowslt.hookrelay.security

import com.sugowslt.hookrelay.delivery.RetryPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WebhookSecurityConfiguration {
    @Bean
    fun webhookUrlPolicy(
        @Value("\${hook-relay.security.allowed-schemes:https}") allowedSchemes: String,
        @Value("\${hook-relay.security.allow-non-public-targets:false}") allowNonPublicTargets: Boolean,
    ): WebhookUrlPolicy {
        val normalizedSchemes = allowedSchemes.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toSet()
        require(normalizedSchemes.isNotEmpty()) { "At least one Webhook URL scheme must be allowed" }
        return WebhookUrlPolicy(
            allowedSchemes = normalizedSchemes,
            allowNonPublicTargets = allowNonPublicTargets,
        )
    }

    @Bean
    fun webhookSignatureService(): WebhookSignatureService = WebhookSignatureService()

    @Bean
    fun retryPolicy(): RetryPolicy = RetryPolicy()
}
