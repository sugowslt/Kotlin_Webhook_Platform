package com.sugowslt.hookrelay.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.sugowslt.hookrelay.common.ApiErrorResponse
import com.sugowslt.hookrelay.common.TraceIdFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Configuration
class OperatorApiSecurityConfiguration {
    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        objectMapper: ObjectMapper,
        @Value("\${hook-relay.security.operator-token:}") operatorToken: String,
    ): SecurityFilterChain {
        val redeliveryMatcher = PathPatternRequestMatcher.pathPattern(
            HttpMethod.POST,
            "/api/v1/deliveries/{deliveryId}/redeliveries",
        )
        val operatorApiMatcher = OrRequestMatcher(
            redeliveryMatcher,
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/deliveries"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/deliveries/{deliveryId}"),
        )
        val authenticationEntryPoint = ApiAuthenticationEntryPoint(objectMapper)
        val accessDeniedHandler = ApiAccessDeniedHandler(objectMapper)

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .authorizeHttpRequests {
                it.requestMatchers(operatorApiMatcher).hasAuthority(OPERATOR_AUTHORITY)
                it.anyRequest().permitAll()
            }
            .addFilterBefore(
                OperatorTokenAuthenticationFilter(operatorApiMatcher, OperatorTokenVerifier(operatorToken)),
                AnonymousAuthenticationFilter::class.java,
            )

        return http.build()
    }

    companion object {
        const val OPERATOR_AUTHORITY = "OPERATOR"
    }
}

internal class OperatorTokenAuthenticationFilter(
    private val protectedRequest: RequestMatcher,
    private val tokenVerifier: OperatorTokenVerifier,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !protectedRequest.matches(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presentedToken = resolveBearerToken(request)
        if (presentedToken != null && tokenVerifier.matches(presentedToken)) {
            val authentication = UsernamePasswordAuthenticationToken.authenticated(
                "operator",
                null,
                listOf(SimpleGrantedAuthority(OperatorApiSecurityConfiguration.OPERATOR_AUTHORITY)),
            )
            val securityContext = SecurityContextHolder.createEmptyContext()
            securityContext.authentication = authentication
            SecurityContextHolder.setContext(securityContext)
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveBearerToken(request: HttpServletRequest): String? {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        val separator = authorization.indexOf(' ')
        if (separator < 0 || !authorization.substring(0, separator).equals("Bearer", ignoreCase = true)) {
            return null
        }
        val token = authorization.substring(separator + 1)
        return token.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }
}

internal class OperatorTokenVerifier(configuredToken: String) {
    private val expectedDigest: ByteArray

    init {
        require(configuredToken.length >= MINIMUM_TOKEN_LENGTH) {
            "Operator token must contain at least $MINIMUM_TOKEN_LENGTH characters"
        }
        expectedDigest = digest(configuredToken)
    }

    fun matches(presentedToken: String): Boolean = MessageDigest.isEqual(
        expectedDigest,
        digest(presentedToken),
    )

    private fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val MINIMUM_TOKEN_LENGTH = 32
    }
}

private class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: org.springframework.security.core.AuthenticationException,
    ) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"hook-relay\"")
        writeSecurityError(
            response = response,
            objectMapper = objectMapper,
            status = HttpServletResponse.SC_UNAUTHORIZED,
            code = "OPERATOR_AUTHENTICATION_REQUIRED",
            message = "A valid operator bearer token is required",
            request = request,
        )
    }
}

private class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: org.springframework.security.access.AccessDeniedException,
    ) {
        writeSecurityError(
            response = response,
            objectMapper = objectMapper,
            status = HttpServletResponse.SC_FORBIDDEN,
            code = "OPERATOR_ACCESS_DENIED",
            message = "Operator authority is required",
            request = request,
        )
    }
}

private fun writeSecurityError(
    response: HttpServletResponse,
    objectMapper: ObjectMapper,
    status: Int,
    code: String,
    message: String,
    request: HttpServletRequest,
) {
    response.status = status
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    objectMapper.writeValue(
        response.outputStream,
        ApiErrorResponse(
            code = code,
            message = message,
            path = request.requestURI,
            traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID),
        ),
    )
}
