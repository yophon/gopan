package xyz.yophon.gopan.core.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.MeQuery
import xyz.yophon.gopan.testutil.FakeGopanServer
import xyz.yophon.gopan.testutil.TestHarness

class SessionManagerTest {
    private lateinit var server: FakeGopanServer
    private lateinit var h: TestHarness

    @Before
    fun setUp() {
        server = FakeGopanServer()
        h = TestHarness(server)
    }

    @After
    fun tearDown() {
        h.close()
        server.close()
    }

    @Test
    fun `login stores the refresh cookie and refresh sends it back without a bearer`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")

        val login = server.server.takeRequest()
        assertThat(login.getHeader("Cookie")).isNull()
        assertThat(login.getHeader("Authorization")).isNull()
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isTrue()
        assertThat(h.store.accessToken).isEqualTo("t1")
        assertThat(h.store.state.value).isEqualTo(AuthState.SignedIn(alice))

        assertThat(h.sessionManager.refresh()).isTrue()

        val refresh = server.server.takeRequest()
        assertThat(refresh.getHeader("Cookie")).contains("gopan_rt=rt1")
        assertThat(refresh.getHeader("Authorization")).isNull()
        assertThat(h.store.accessToken).isEqualTo("t2")
        // cookie 轮换:下一次带的是新 cookie
        assertThat(h.sessionManager.refresh()).isTrue()
        assertThat(server.server.takeRequest().getHeader("Cookie")).contains("gopan_rt=rt-t2")
        assertThat(server.refreshCount.get()).isEqualTo(2)
    }

    @Test
    fun `two concurrent UNAUTHENTICATED responses trigger a single refresh and both replays succeed`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        // 服务端悄悄让 t1 失效:业务请求会先吃一个 UNAUTHENTICATED
        server.validToken = "t2"
        server.nextRefreshToken = "t2"
        server.refreshDelayMs = 300

        val results = listOf(
            async { h.apollo.query(MeQuery()).execute().requireData().me.userFields.username },
            async { h.apollo.query(MeQuery()).execute().requireData().me.userFields.username },
        ).awaitAll()

        assertThat(results).containsExactly("alice", "alice")
        assertThat(server.refreshCount.get()).isEqualTo(1)
        assertThat(h.store.accessToken).isEqualTo("t2")
        // 两次首发(401)+ 两次重放
        assertThat(server.meCount.get()).isEqualTo(4)
    }

    @Test
    fun `rejected refresh signs out and clears the cookie`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        server.refreshOk = false

        assertThat(h.sessionManager.refresh()).isFalse()

        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(h.store.accessToken).isNull()
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
        assertThat(h.secureStore.get(SessionManager.KEY_LAST_USER)).isNull()
    }

    @Test
    fun `business request whose refresh is rejected surfaces UNAUTHENTICATED and signs out`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        server.validToken = "other"
        server.refreshOk = false

        val error = runCatching { h.apollo.query(MeQuery()).execute().requireData() }.exceptionOrNull()

        assertThat(error).isInstanceOf(GopanException::class.java)
        assertThat((error as GopanException).isUnauthenticated).isTrue()
        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(server.refreshCount.get()).isEqualTo(1)
    }

    @Test
    fun `bootstrap without cookie signs out without touching the network`() = runBlocking {
        h.sessionManager.bootstrap()

        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(server.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `bootstrap with cookie refreshes into SignedIn`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        // 模拟进程重启:同一份加密存储,新的内存态
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            assertThat(restarted.store.state.value).isEqualTo(AuthState.Loading)
            restarted.sessionManager.bootstrap()
            assertThat(restarted.store.state.value).isEqualTo(AuthState.SignedIn(alice))
            assertThat(restarted.store.accessToken).isEqualTo("t2")
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `bootstrap offline falls back to the cached user and keeps the cookie`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            server.close()
            restarted.sessionManager.bootstrap()
            assertThat(restarted.store.state.value).isEqualTo(AuthState.SignedIn(alice))
            assertThat(restarted.store.accessToken).isNull()
            assertThat(restarted.cookieJar.hasCookie("gopan_rt")).isTrue()
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `bootstrap with a cached user signs in before the refresh lands`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            server.refreshDelayMs = 400
            val booting = async { restarted.sessionManager.bootstrap() }
            delay(150)
            // 还没等到 refresh:已经按上次的用户进了主界面,token 暂时为空
            assertThat(restarted.store.state.value).isEqualTo(AuthState.SignedIn(alice))
            assertThat(restarted.store.accessToken).isNull()

            booting.await()
            assertThat(restarted.store.accessToken).isEqualTo("t2")
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `bootstrap with a cached user whose session was revoked ends signed out`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            server.refreshOk = false
            restarted.sessionManager.bootstrap()
            assertThat(restarted.store.state.value).isEqualTo(AuthState.SignedOut)
            assertThat(restarted.cookieJar.hasCookie("gopan_rt")).isFalse()
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `bootstrap without a cached user waits for the refresh and signs out when offline`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        h.secureStore.put(SessionManager.KEY_LAST_USER, null)
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            server.close()
            restarted.sessionManager.bootstrap()
            assertThat(restarted.store.state.value).isEqualTo(AuthState.SignedOut)
            // 只是联系不上,不是被拒:cookie 留着,下次启动还能续登
            assertThat(restarted.cookieJar.hasCookie("gopan_rt")).isTrue()
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `a request without a token waits for the startup refresh instead of bouncing off UNAUTHENTICATED`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        val restarted = TestHarness(server, secureStore = h.secureStore)
        try {
            server.refreshDelayMs = 300
            val booting = async { restarted.sessionManager.bootstrap() }
            delay(100)
            val me = restarted.apollo.query(MeQuery()).execute().requireData().me.userFields.username
            booting.await()

            assertThat(me).isEqualTo("alice")
            assertThat(server.refreshCount.get()).isEqualTo(1)
            // 一次就带着新 token 成功,没有先吃一个 UNAUTHENTICATED 再重放
            assertThat(server.meCount.get()).isEqualTo(1)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `logout clears everything even though the server already forgot the cookie`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")

        h.sessionManager.logout()

        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
        assertThat(h.store.accessToken).isNull()
        server.server.takeRequest() // login
        val logout = server.server.takeRequest()
        assertThat(logout.getHeader("Cookie")).contains("gopan_rt=rt1")
        assertThat(logout.getHeader("Authorization")).isNull()
    }

    @Test
    fun `logout during an in-flight refresh does not resurrect the session`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        server.refreshDelayMs = 400
        val refreshing = async { h.sessionManager.refresh() }
        delay(150) // refresh 已经在路上,它的响应会带一枚新 cookie

        h.sessionManager.logout()
        refreshing.await()

        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(h.store.accessToken).isNull()
        // 迟到的 refresh 写进来的新 cookie 也被清掉,下次冷启动不会自动登回去
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
        assertThat(h.secureStore.get(SessionManager.KEY_LAST_USER)).isNull()
    }

    @Test
    fun `after logout a stale 401 does not refresh with leftover credentials`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        h.sessionManager.logout()
        val before = server.refreshCount.get()

        val outcome = h.sessionManager.refreshAfterUnauthenticated("t1")

        assertThat(outcome).isEqualTo(xyz.yophon.gopan.core.network.RefreshOutcome.SignedOut)
        assertThat(server.refreshCount.get()).isEqualTo(before)
    }

    @Test
    fun `sign-out hooks run on logout and when the server rejects the session`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        h.sessionManager.logout()
        assertThat(h.signOutHookCalls.get()).isEqualTo(1)

        h.sessionManager.loginWithPassword("alice", "secret")
        server.refreshOk = false
        assertThat(h.sessionManager.refresh()).isFalse()
        assertThat(h.signOutHookCalls.get()).isEqualTo(2)
    }

    @Test
    fun `refresh failing with a non-auth error after the server dropped the cookie signs out`() = runBlocking {
        h.sessionManager.loginWithPassword("alice", "secret")
        // 模拟服务端回了 INTERNAL 但已经删掉了 cookie:客户端不能停在"已登录、无凭证"
        h.cookieJar.clear()

        assertThat(h.sessionManager.refresh()).isFalse()
        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
    }

    private val alice = UserSummary(id = "u1", username = "alice", quotaBytes = 1073741824, usedBytes = 12345, isAdmin = false)
}
