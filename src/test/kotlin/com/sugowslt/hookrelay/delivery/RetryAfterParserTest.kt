package com.sugowslt.hookrelay.delivery

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryAfterParserTest {
    private val now = Instant.parse("2026-09-11T00:00:00Z")

    @Test
    fun `초 단위 Retry-After를 읽는다`() {
        assertEquals(Duration.ofSeconds(15), RetryAfterParser.parse("15", now))
    }

    @Test
    fun `HTTP 날짜 형식의 Retry-After를 읽는다`() {
        assertEquals(
            Duration.ofSeconds(30),
            RetryAfterParser.parse("Fri, 11 Sep 2026 00:00:30 GMT", now),
        )
    }

    @Test
    fun `잘못된 Retry-After는 무시한다`() {
        assertEquals(null, RetryAfterParser.parse("later", now))
    }
}
