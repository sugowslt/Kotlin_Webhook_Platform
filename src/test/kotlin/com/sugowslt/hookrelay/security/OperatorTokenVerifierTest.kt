package com.sugowslt.hookrelay.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorTokenVerifierTest {
    @Test
    fun `설정한 운영자 토큰과 같은 값만 허용한다`() {
        val verifier = OperatorTokenVerifier("operator-token-that-contains-32-characters")

        assertTrue(verifier.matches("operator-token-that-contains-32-characters"))
        assertFalse(verifier.matches("operator-token-that-contains-32-characterX"))
    }

    @Test
    fun `길이가 다른 토큰도 거부한다`() {
        val verifier = OperatorTokenVerifier("operator-token-that-contains-32-characters")

        assertFalse(verifier.matches("short-token"))
    }

    @Test
    fun `32자보다 짧은 운영자 토큰으로는 시작하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            OperatorTokenVerifier("short-operator-token")
        }
    }
}
