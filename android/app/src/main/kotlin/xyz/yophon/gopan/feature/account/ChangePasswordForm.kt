package xyz.yophon.gopan.feature.account

/** 改密码表单的校验结果;规则与服务端一致(8~72 字节,bcrypt 上限)。 */
enum class ChangePasswordError {
    OLD_EMPTY,
    NEW_EMPTY,
    NEW_TOO_SHORT,
    NEW_TOO_LONG,
    NEW_SAME_AS_OLD,
    CONFIRM_MISMATCH,
}

object ChangePasswordForm {
    const val MIN_LENGTH = 8
    const val MAX_BYTES = 72

    /**
     * 校验顺序:旧密码非空 → 新密码非空 → 长度 → 不与旧密码相同 → 两次一致。
     * 返回 null 表示可提交。
     */
    fun validate(oldPassword: String, newPassword: String, confirm: String): ChangePasswordError? = when {
        oldPassword.isEmpty() -> ChangePasswordError.OLD_EMPTY
        newPassword.isEmpty() -> ChangePasswordError.NEW_EMPTY
        newPassword.length < MIN_LENGTH -> ChangePasswordError.NEW_TOO_SHORT
        newPassword.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> ChangePasswordError.NEW_TOO_LONG
        newPassword == oldPassword -> ChangePasswordError.NEW_SAME_AS_OLD
        confirm != newPassword -> ChangePasswordError.CONFIRM_MISMATCH
        else -> null
    }

    /** 三个框都有内容才启用提交按钮(错误提示另行显示) */
    fun canSubmit(oldPassword: String, newPassword: String, confirm: String): Boolean =
        oldPassword.isNotEmpty() && newPassword.isNotEmpty() && confirm.isNotEmpty()
}
