package xyz.yophon.gopan.feature.login

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.io.IOException

class LoginRulesTest {

    @Test
    fun `register length rules count UTF-8 bytes like the Go server`() {
        // 11 个汉字 = 33 字节,超过 32
        val elevenHan = "一二三四五六七八九十一"
        assertThat(LoginRules.validateRegister(elevenHan, "secret-12", "secret-12"))
            .isEqualTo(FieldError(LoginField.USERNAME, LoginRules.TEXT_USERNAME_LENGTH))
        // 10 个汉字 = 30 字节,可以
        assertThat(LoginRules.validateRegister("一二三四五六七八九十", "secret-12", "secret-12")).isNull()
        // 3 个汉字密码 = 9 字节 ≥ 8
        assertThat(LoginRules.validateRegister("alice", "密码密", "密码密")).isNull()
        // 2 个汉字密码 = 6 字节 < 8
        assertThat(LoginRules.validateRegister("alice", "密码", "密码"))
            .isEqualTo(FieldError(LoginField.PASSWORD, LoginRules.TEXT_PASSWORD_LENGTH))
        assertThat(LoginRules.validateRegister("alice", "a".repeat(73), "a".repeat(73)))
            .isEqualTo(FieldError(LoginField.PASSWORD, LoginRules.TEXT_PASSWORD_LENGTH))
    }

    @Test
    fun `register checks fields in display order`() {
        assertThat(LoginRules.validateRegister("", "", "")?.field).isEqualTo(LoginField.USERNAME)
        assertThat(LoginRules.validateRegister("alice", "", "")?.field).isEqualTo(LoginField.PASSWORD)
        assertThat(LoginRules.validateRegister("alice", "secret-12", "")?.field).isEqualTo(LoginField.CONFIRM)
        assertThat(LoginRules.validateRegister("alice", "secret-12", "secret-12")).isNull()
    }

    @Test
    fun `login only requires the fields to be present`() {
        assertThat(LoginRules.validateLogin("", "x")?.field).isEqualTo(LoginField.USERNAME)
        assertThat(LoginRules.validateLogin("a", "")?.field).isEqualTo(LoginField.PASSWORD)
        assertThat(LoginRules.validateLogin("a", "b")).isNull()
    }

    @Test
    fun `error texts follow the product decisions`() {
        fun text(code: String, serverMessage: String? = null) =
            LoginRules.errorText(GopanException(code, "原始文案", serverMessage = serverMessage))

        assertThat(text(GopanErrors.ID_CANCELLED)).isNull()
        assertThat(text(GopanErrors.BAD_CREDENTIALS)).isEqualTo("用户名或密码错误")
        assertThat(text(GopanErrors.ACCOUNT_DISABLED)).isEqualTo("账号已被禁用")
        assertThat(text(GopanErrors.ID_REQUIRED)).isEqualTo("请使用 Yophon ID 登录")
        assertThat(text(GopanErrors.USERNAME_TAKEN)).isEqualTo("用户名已被占用")
        assertThat(text(GopanErrors.REGISTER_CLOSED)).isEqualTo("注册已关闭")
        assertThat(text(GopanErrors.RATE_LIMITED)).contains("尝试过于频繁")
        assertThat(text(GopanErrors.INVALID_INPUT, "密码长度需在 8~72 之间")).isEqualTo("密码长度需在 8~72 之间")
        assertThat(text(GopanErrors.INVALID_INPUT, "  ")).isEqualTo(LoginRules.TEXT_INVALID_INPUT)
        assertThat(text(GopanErrors.NETWORK)).contains("网络不可用")
        assertThat(text(GopanErrors.ID_FORBIDDEN)).isEqualTo("账号未授权或登录暂不可用,请重试")
        assertThat(text(GopanErrors.ID_TICKET_DENIED)).isEqualTo("账号未授权或登录暂不可用,请重试")
        // 其余错误用 GopanException 自带的中文
        assertThat(text(GopanErrors.ID_ENTRY_MISMATCH)).isEqualTo("原始文案")
        // 非 GopanException 先转成 GopanException 再映射
        assertThat(LoginRules.errorText(IOException("boom"))).contains("网络不可用")
        assertThat(LoginRules.errorText(IllegalStateException("x"))).isNotEmpty()
    }

    @Test
    fun `only REGISTER_CLOSED collapses the register tab`() {
        assertThat(LoginRules.closesRegistration(GopanException(GopanErrors.REGISTER_CLOSED, "注册已关闭"))).isTrue()
        assertThat(LoginRules.closesRegistration(GopanException(GopanErrors.USERNAME_TAKEN, "占用"))).isFalse()
        assertThat(LoginRules.closesRegistration(IOException())).isFalse()
    }
}
