package xyz.yophon.gopan.core.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PkceTest {
    @Test
    fun `verifier is 43 url-safe characters and differs per call`() {
        val a = Pkce.generateVerifier()
        val b = Pkce.generateVerifier()
        assertThat(a).matches("^[A-Za-z0-9_-]{43}$")
        assertThat(b).matches("^[A-Za-z0-9_-]{43}$")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `challenge is the RFC 7636 S256 example`() {
        // RFC 7636 Appendix B
        val challenge = Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertThat(challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertThat(challenge).hasLength(43)
    }
}
