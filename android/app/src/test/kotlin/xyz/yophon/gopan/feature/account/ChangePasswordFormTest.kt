package xyz.yophon.gopan.feature.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChangePasswordFormTest {
    private fun validate(old: String, new: String, confirm: String = new) = ChangePasswordForm.validate(old, new, confirm)

    @Test
    fun `valid input passes`() {
        assertThat(validate("oldpass123", "newpass456")).isNull()
    }

    @Test
    fun `old password is required first`() {
        assertThat(validate("", "newpass456")).isEqualTo(ChangePasswordError.OLD_EMPTY)
        // 旧密码为空时优先报旧密码,即使新密码也有问题
        assertThat(validate("", "short")).isEqualTo(ChangePasswordError.OLD_EMPTY)
    }

    @Test
    fun `new password must be present and at least 8 chars`() {
        assertThat(validate("oldpass123", "")).isEqualTo(ChangePasswordError.NEW_EMPTY)
        assertThat(validate("oldpass123", "1234567")).isEqualTo(ChangePasswordError.NEW_TOO_SHORT)
        assertThat(validate("oldpass123", "12345678")).isNull()
    }

    @Test
    fun `new password is limited to 72 bytes in UTF-8`() {
        val ascii72 = "a".repeat(72)
        assertThat(validate("oldpass123", ascii72)).isNull()
        assertThat(validate("oldpass123", ascii72 + "a")).isEqualTo(ChangePasswordError.NEW_TOO_LONG)
        // 中文每字 3 字节:25 个字 = 75 字节,超限;24 个字 = 72 字节,刚好
        assertThat(validate("oldpass123", "密".repeat(25))).isEqualTo(ChangePasswordError.NEW_TOO_LONG)
        assertThat(validate("oldpass123", "密".repeat(24))).isNull()
    }

    @Test
    fun `new password must differ from old`() {
        assertThat(validate("samepass123", "samepass123")).isEqualTo(ChangePasswordError.NEW_SAME_AS_OLD)
    }

    @Test
    fun `confirmation must match`() {
        assertThat(validate("oldpass123", "newpass456", "newpass457")).isEqualTo(ChangePasswordError.CONFIRM_MISMATCH)
        assertThat(validate("oldpass123", "newpass456", "")).isEqualTo(ChangePasswordError.CONFIRM_MISMATCH)
    }

    @Test
    fun `submit button needs all three fields`() {
        assertThat(ChangePasswordForm.canSubmit("a", "b", "c")).isTrue()
        assertThat(ChangePasswordForm.canSubmit("", "b", "c")).isFalse()
        assertThat(ChangePasswordForm.canSubmit("a", "", "c")).isFalse()
        assertThat(ChangePasswordForm.canSubmit("a", "b", "")).isFalse()
    }
}
