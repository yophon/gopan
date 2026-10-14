package xyz.yophon.gopan.feature.login

import android.content.Intent

/** 登录页的三种视图:Yophon ID 主入口;展开后的密码登录 tab;注册 tab */
enum class LoginMode { YOPHON_ID, PASSWORD, REGISTER }

/** 本地校验出错的输入框,UI 据此把对应输入框标红并在其下方显示文案 */
enum class LoginField { USERNAME, PASSWORD, CONFIRM }

data class LoginUiState(
    val mode: LoginMode = LoginMode.YOPHON_ID,
    /** 任一登录动作进行中(含等待账号中心授权页返回);期间按钮禁用并显示加载 */
    val busy: Boolean = false,
    /** 给用户看的错误文案;null 表示没有错误 */
    val error: String? = null,
    /** [error] 对应的输入框;服务端错误为 null,显示在表单下方的横幅里 */
    val errorField: LoginField? = null,
    /** 服务端是否开放注册:null 未知(默认显示注册 tab);收到 REGISTER_CLOSED 后为 false */
    val registerOpen: Boolean? = null,
) {
    val showRegisterTab: Boolean get() = registerOpen != false

    /** 是否展开了密码登录 / 注册面板 */
    val passwordPanelVisible: Boolean get() = mode != LoginMode.YOPHON_ID

    /** 不属于某个输入框的错误(服务端错误、Yophon ID 链路错误) */
    val bannerError: String? get() = if (errorField == null) error else null

    fun fieldError(field: LoginField): String? = if (errorField == field) error else null
}

/** ViewModel → UI 的一次性事件 */
sealed interface LoginEvent {
    /** 用 ActivityResultLauncher 启动 AppAuth 的授权页(Custom Tabs) */
    data class LaunchAuthorization(val intent: Intent) : LoginEvent
}
