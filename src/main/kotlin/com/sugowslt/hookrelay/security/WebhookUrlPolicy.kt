package com.sugowslt.hookrelay.security

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

class WebhookUrlPolicy(
    private val allowedSchemes: Set<String> = setOf("https"),
    private val allowNonPublicTargets: Boolean = false,
    private val hostResolver: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() },
) {
    fun validate(rawUrl: String): ValidatedWebhookUrl {
        val uri = runCatching { URI(rawUrl) }
            .getOrElse { throw InvalidWebhookUrlException("WEBHOOK_URL_INVALID", "Webhook URL is invalid") }
        val scheme = uri.scheme?.lowercase()
            ?: throw InvalidWebhookUrlException("WEBHOOK_URL_SCHEME_REQUIRED", "Webhook URL scheme is required")
        if (scheme !in allowedSchemes) {
            throw InvalidWebhookUrlException("WEBHOOK_URL_SCHEME_NOT_ALLOWED", "Webhook URL scheme is not allowed")
        }
        if (uri.rawUserInfo != null) {
            throw InvalidWebhookUrlException("WEBHOOK_URL_USERINFO_NOT_ALLOWED", "Webhook URL userinfo is not allowed")
        }
        if (uri.rawFragment != null) {
            throw InvalidWebhookUrlException("WEBHOOK_URL_FRAGMENT_NOT_ALLOWED", "Webhook URL fragment is not allowed")
        }

        val host = uri.host
            ?.takeIf { it.isNotBlank() }
            ?.let(IDN::toASCII)
            ?.lowercase()
            ?: throw InvalidWebhookUrlException("WEBHOOK_URL_HOST_REQUIRED", "Webhook URL host is required")
        if (!allowNonPublicTargets && (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local"))) {
            throw InvalidWebhookUrlException("WEBHOOK_URL_HOST_NOT_PUBLIC", "Webhook URL host is not public")
        }

        val addresses = runCatching { hostResolver.resolve(host) }
            .getOrElse { throw InvalidWebhookUrlException("WEBHOOK_URL_HOST_UNRESOLVED", "Webhook URL host cannot be resolved") }
        if (addresses.isEmpty() || (!allowNonPublicTargets && addresses.any(::isNonPublicAddress))) {
            throw InvalidWebhookUrlException("WEBHOOK_URL_HOST_NOT_PUBLIC", "Webhook URL host is not public")
        }

        return ValidatedWebhookUrl(uri = uri, resolvedAddresses = addresses)
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isNonPublicIpv4(address.address)
            is Inet6Address -> isUniqueLocalIpv6(address.address)
            else -> true
        }
    }

    private fun isNonPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19) ||
            first >= 224
    }

    private fun isUniqueLocalIpv6(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and 0xfe) == 0xfc
}

data class ValidatedWebhookUrl(
    val uri: URI,
    val resolvedAddresses: List<InetAddress>,
)

class InvalidWebhookUrlException(
    val errorCode: String,
    override val message: String,
) : IllegalArgumentException(message)
