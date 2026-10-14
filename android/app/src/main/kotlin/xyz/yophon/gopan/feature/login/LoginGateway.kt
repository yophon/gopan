package xyz.yophon.gopan.feature.login

import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.auth.SessionManager
import xyz.yophon.gopan.core.auth.YophonIdLogin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录页对会话层的全部依赖。接口化是为了 [LoginViewModel] 单测能换成假实现;
 * 生产实现 [SessionLoginGateway] 只是转调 core.auth。
 * 每个方法成功返回时 AuthState 已经是 SignedIn(由 SessionManager 置位),根导航据此切到主界面。
 */
interface LoginGateway {
    suspend fun loginWithPassword(username: String, password: String)

    suspend fun register(username: String, password: String)

    /** Yophon ID 第 1 步:取发现文档并构造授权 Intent(PKCE 由 AppAuth 自动生成) */
    suspend fun buildYophonIdIntent(): Intent

    /** Yophon ID 后续步骤:解析授权回调 → 换账号中心令牌 → 换票据 → 建立 gopan 会话 */
    suspend fun completeYophonId(result: Intent?)
}

@Singleton
class SessionLoginGateway @Inject constructor(
    private val sessionManager: SessionManager,
    private val yophonIdLogin: YophonIdLogin,
) : LoginGateway {

    override suspend fun loginWithPassword(username: String, password: String) =
        sessionManager.loginWithPassword(username, password)

    override suspend fun register(username: String, password: String) =
        sessionManager.register(username, password)

    override suspend fun buildYophonIdIntent(): Intent = yophonIdLogin.buildAuthorizationIntent()

    override suspend fun completeYophonId(result: Intent?) = yophonIdLogin.completeSignIn(result)
}

/** 本功能线自己的 Hilt 模块(Hilt 会聚合所有 @InstallIn 模块,不需要动 di/AppModule) */
@Module
@InstallIn(SingletonComponent::class)
abstract class LoginModule {
    @Binds
    abstract fun bindLoginGateway(impl: SessionLoginGateway): LoginGateway
}
