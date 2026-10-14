package xyz.yophon.gopan.feature.login

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred

/**
 * 假的会话层:记录调用、按需抛错、可用 [gate] 卡住请求以观察 busy。
 * 成功时什么都不做(真实实现会把 AuthState 置为 SignedIn,那是 SessionManager 的事,不在这里测)。
 */
class FakeLoginGateway : LoginGateway {
    val calls = mutableListOf<String>()

    var loginError: Throwable? = null
    var registerError: Throwable? = null
    var buildError: Throwable? = null
    var completeError: Throwable? = null

    /** 非 null 时 login / register / complete 都会先等它完成 */
    var gate: CompletableDeferred<Unit>? = null

    /** 返回给 UI 的授权 Intent(android.jar 桩,returnDefaultValues 下可以直接构造) */
    val authorizationIntent = Intent()

    override suspend fun loginWithPassword(username: String, password: String) {
        calls += "login:$username:$password"
        gate?.await()
        loginError?.let { throw it }
    }

    override suspend fun register(username: String, password: String) {
        calls += "register:$username:$password"
        gate?.await()
        registerError?.let { throw it }
    }

    override suspend fun buildYophonIdIntent(): Intent {
        calls += "build"
        buildError?.let { throw it }
        return authorizationIntent
    }

    override suspend fun completeYophonId(result: Intent?) {
        calls += "complete:" + (if (result == null) "null" else "intent")
        gate?.await()
        completeError?.let { throw it }
    }
}
