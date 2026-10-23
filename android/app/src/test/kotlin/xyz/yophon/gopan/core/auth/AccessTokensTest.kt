package xyz.yophon.gopan.core.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

/** 测试用:造一枚带 exp 的 JWT(签名部分随便,客户端不验签) */
fun testJwt(expEpochSeconds: Long, sub: String = "u1"): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
    val payload = enc.encodeToString("""{"scope":"user","sub":"$sub","exp":$expEpochSeconds}""".toByteArray())
    return "$header.$payload.sig"
}

class AccessTokensTest {
    @Test
    fun `reads exp from a JWT`() {
        assertThat(AccessTokens.expiresAtMillis(testJwt(1_792_661_149))).isEqualTo(1_792_661_149_000L)
    }

    @Test
    fun `anything that is not a JWT with exp reads as unknown`() {
        assertThat(AccessTokens.expiresAtMillis("t1")).isNull()
        assertThat(AccessTokens.expiresAtMillis("a.b.c")).isNull()
        val noExp = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"u1"}""".toByteArray())
        assertThat(AccessTokens.expiresAtMillis("h.$noExp.s")).isNull()
    }
}
