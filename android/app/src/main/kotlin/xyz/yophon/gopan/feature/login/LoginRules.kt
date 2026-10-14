package xyz.yophon.gopan.feature.login

import android.content.ActivityNotFoundException
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException

/** 本地校验结果:哪个输入框、什么文案 */
data class FieldError(val field: LoginField, val message: String)

/**
 * 登录页的纯逻辑:表单校验与错误码 → 文案,不依赖 Android 运行时,直接单测。
 * 长度规则与服务端 service/auth.go 相同(Go 的 len 按字节,这里也按 UTF-8 字节数)。
 */
object LoginRules {
    const val USERNAME_MIN_BYTES = 2
    const val USERNAME_MAX_BYTES = 32
    const val PASSWORD_MIN_BYTES = 8
    const val PASSWORD_MAX_BYTES = 72

    const val TEXT_USERNAME_REQUIRED = "请输入用户名"
    const val TEXT_PASSWORD_REQUIRED = "请输入密码"
    const val TEXT_USERNAME_LENGTH = "用户名长度需在 2~32 之间"
    const val TEXT_PASSWORD_LENGTH = "密码长度需在 8~72 之间"
    const val TEXT_PASSWORD_MISMATCH = "两次输入的密码不一致"

    const val TEXT_BAD_CREDENTIALS = "用户名或密码错误"
    const val TEXT_ACCOUNT_DISABLED = "账号已被禁用"
    const val TEXT_ID_REQUIRED = "请使用 Yophon ID 登录"
    const val TEXT_USERNAME_TAKEN = "用户名已被占用"
    const val TEXT_REGISTER_CLOSED = "注册已关闭"
    const val TEXT_RATE_LIMITED = "尝试过于频繁,请稍后再试"
    const val TEXT_INVALID_INPUT = "输入无效"
    const val TEXT_NETWORK = "网络不可用,请检查网络后重试"
    const val TEXT_ID_UNAUTHORIZED = "账号未授权或登录暂不可用,请重试"
    const val TEXT_NO_BROWSER = "没有可用的浏览器,无法打开账号中心"
    const val TEXT_FALLBACK = "登录失败,请重试"

    /** 密码登录前的本地校验;username 已 trim */
    fun validateLogin(username: String, password: String): FieldError? = when {
        username.isEmpty() -> FieldError(LoginField.USERNAME, TEXT_USERNAME_REQUIRED)
        password.isEmpty() -> FieldError(LoginField.PASSWORD, TEXT_PASSWORD_REQUIRED)
        else -> null
    }

    /** 注册前的本地校验(与服务端同样的长度规则,省一次往返);username 已 trim */
    fun validateRegister(username: String, password: String, confirm: String): FieldError? = when {
        username.isEmpty() -> FieldError(LoginField.USERNAME, TEXT_USERNAME_REQUIRED)
        username.utf8Length() !in USERNAME_MIN_BYTES..USERNAME_MAX_BYTES ->
            FieldError(LoginField.USERNAME, TEXT_USERNAME_LENGTH)
        password.isEmpty() -> FieldError(LoginField.PASSWORD, TEXT_PASSWORD_REQUIRED)
        password.utf8Length() !in PASSWORD_MIN_BYTES..PASSWORD_MAX_BYTES ->
            FieldError(LoginField.PASSWORD, TEXT_PASSWORD_LENGTH)
        confirm != password -> FieldError(LoginField.CONFIRM, TEXT_PASSWORD_MISMATCH)
        else -> null
    }

    /**
     * 任意异常 → 登录页文案;返回 null 表示不需要提示(用户自己取消了 Yophon ID 授权)。
     * 产品定下来的文案优先;INVALID_INPUT 用服务端 message;其余 GopanException 用它自带的中文。
     */
    fun errorText(e: Throwable): String? {
        if (e is ActivityNotFoundException) return TEXT_NO_BROWSER
        val ge = e.toGopanException()
        return when (ge.code) {
            GopanErrors.ID_CANCELLED -> null
            GopanErrors.BAD_CREDENTIALS -> TEXT_BAD_CREDENTIALS
            GopanErrors.ACCOUNT_DISABLED -> TEXT_ACCOUNT_DISABLED
            GopanErrors.ID_REQUIRED -> TEXT_ID_REQUIRED
            GopanErrors.USERNAME_TAKEN -> TEXT_USERNAME_TAKEN
            GopanErrors.REGISTER_CLOSED -> TEXT_REGISTER_CLOSED
            GopanErrors.RATE_LIMITED -> TEXT_RATE_LIMITED
            GopanErrors.INVALID_INPUT -> ge.serverMessage?.trim()?.takeIf { it.isNotEmpty() } ?: TEXT_INVALID_INPUT
            GopanErrors.NETWORK -> TEXT_NETWORK
            GopanErrors.ID_TICKET_DENIED, GopanErrors.ID_FORBIDDEN -> TEXT_ID_UNAUTHORIZED
            else -> ge.message?.takeIf { it.isNotBlank() } ?: TEXT_FALLBACK
        }
    }

    /** 收到这个错误后注册 tab 应当收起 */
    fun closesRegistration(e: Throwable): Boolean = (e as? GopanException)?.code == GopanErrors.REGISTER_CLOSED

    private fun String.utf8Length(): Int = toByteArray(Charsets.UTF_8).size
}
