package xyz.yophon.gopan.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 移动票据用的 PKCE:verifier 32 字节随机 → 43 位 base64url(无填充);challenge = base64url(SHA-256(verifier))。 */
object Pkce {
    fun generateVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
