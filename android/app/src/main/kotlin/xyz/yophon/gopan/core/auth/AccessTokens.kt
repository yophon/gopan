package xyz.yophon.gopan.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** access token(JWT)的过期时间,冷启动时判断存下的那枚还能不能直接用 */
internal object AccessTokens {
    /** exp(epoch 毫秒);不是 JWT 或没有 exp 时返回 null,当作不能复用 */
    fun expiresAtMillis(token: String): Long? = runCatching {
        val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
        claims["exp"]?.jsonPrimitive?.longOrNull?.times(1000)
    }.getOrNull()
}
