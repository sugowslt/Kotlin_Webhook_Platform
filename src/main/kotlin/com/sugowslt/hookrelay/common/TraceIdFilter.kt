package com.sugowslt.hookrelay.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(TraceIdFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = request.getHeader(TRACE_ID_HEADER)
            ?.takeIf { it.matches(TRACE_ID_PATTERN) }
            ?: UUID.randomUUID().toString().replace("-", "")

        MDC.put(MDC_TRACE_ID, traceId)
        response.setHeader(TRACE_ID_HEADER, traceId)
        val startedAt = System.nanoTime()

        try {
            filterChain.doFilter(request, response)
        } finally {
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            log.info(
                "http.request method={} path={} status={} elapsedMillis={} traceId={}",
                request.method,
                request.requestURI,
                response.status,
                elapsedMillis,
                traceId,
            )
            MDC.remove(MDC_TRACE_ID)
        }
    }

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"
        const val MDC_TRACE_ID = "traceId"
        private val TRACE_ID_PATTERN = Regex("^[a-fA-F0-9]{32}$")
    }
}
