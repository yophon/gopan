package xyz.yophon.gopan.feature.account.security

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.security.AccessRoot
import xyz.yophon.gopan.core.data.security.ApiKey
import xyz.yophon.gopan.core.data.security.AppPassword
import xyz.yophon.gopan.core.data.security.AuditEvent
import xyz.yophon.gopan.core.data.security.CreatedApiKey
import xyz.yophon.gopan.core.data.security.CredentialType
import xyz.yophon.gopan.core.data.security.DeviceSession
import xyz.yophon.gopan.core.data.security.OAuthGrant
import xyz.yophon.gopan.core.data.security.SecurityRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.shares.testConfig

class FakeSecurityRepository : SecurityRepository {
    var sessions = mutableListOf<DeviceSession>()
    val revokedSessions = mutableListOf<String>()
    var passwords = mutableListOf<AppPassword>()
    var keys = mutableListOf<ApiKey>()
    var grants = mutableListOf<OAuthGrant>()
    var roots = mutableListOf<AccessRoot>()
    val createdKeys = mutableListOf<Pair<String, List<String>>>()
    val rootCalls = mutableListOf<Triple<String, CredentialType, String?>>()
    var rootError: GopanException? = null
    var auditPages = ArrayDeque<List<AuditEvent>>()
    val auditCalls = mutableListOf<Long?>()

    override suspend fun sessions() = sessions.toList()
    override suspend fun revokeSession(familyId: String) {
        revokedSessions += familyId
        sessions = sessions.map { if (it.familyId == familyId) it.copy(active = false) else it }.toMutableList()
    }

    override suspend fun appPasswords() = passwords.toList()
    override suspend fun createAppPassword(name: String): String {
        passwords += AppPassword("p${passwords.size}", name, "2026-10-14T00:00:00Z", null)
        return "secret-$name"
    }

    override suspend fun revokeAppPassword(id: String) {
        passwords.removeAll { it.id == id }
    }

    override suspend fun apiKeys() = keys.toList()
    override suspend fun createApiKey(name: String, scopes: List<String>): CreatedApiKey {
        createdKeys += name to scopes
        val key = ApiKey("k${createdKeys.size}", name, scopes, "2026-10-14T00:00:00Z", null)
        keys.add(0, key)
        return CreatedApiKey("gpk_secret", key)
    }

    override suspend fun revokeApiKey(id: String) {
        keys.removeAll { it.id == id }
    }

    override suspend fun oauthGrants() = grants.toList()
    override suspend fun revokeOAuthGrant(id: String) {
        grants.removeAll { it.id == id }
    }

    override suspend fun accessRoots() = roots.toList()
    override suspend fun setAccessRoot(credentialId: String, type: CredentialType, rootPath: String?) {
        rootCalls += Triple(credentialId, type, rootPath)
        rootError?.let { throw it }
        roots.removeAll { it.credentialId == credentialId && it.type == type }
        if (rootPath != null) roots += AccessRoot(credentialId, type, "r", rootPath.trimStart('/'))
    }

    override suspend fun audit(beforeId: Long?, limit: Int): List<AuditEvent> {
        auditCalls += beforeId
        return auditPages.removeFirstOrNull() ?: emptyList()
    }
}

private fun session(id: String, current: Boolean = false, active: Boolean = true, lastSeen: String = "2026-10-14T00:00:00Z") =
    DeviceSession(id, "Mozilla/5.0 (Windows NT 10.0) Chrome/120", "1.1.1.1", "2026-10-01T00:00:00Z", lastSeen, active, current)

private fun audit(id: Long) = AuditEvent(id, "cred", "api_key", "user", "list_files", "ok", null, "2026-10-14T00:00:00Z")

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeSecurityRepository()
    private val store = SessionStore()

    private fun signIn(admin: Boolean) = store.setSignedIn("t", UserSummary("u1", "alice", 0, 0, admin))

    @Test
    fun `devices list this device first, then active ones, revoked last`() = runTest {
        repo.sessions = mutableListOf(
            session("old", active = false, lastSeen = "2026-10-14T09:00:00Z"),
            session("phone", lastSeen = "2026-10-13T00:00:00Z"),
            session("me", current = true, lastSeen = "2026-10-10T00:00:00Z"),
            session("pc", lastSeen = "2026-10-14T08:00:00Z"),
        )
        val vm = DevicesViewModel(repo)
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.familyId }).containsExactly("me", "pc", "phone", "old").inOrder()
    }

    @Test
    fun `revoking another device marks it inactive, this device cannot be revoked here`() = runTest {
        repo.sessions = mutableListOf(session("me", current = true), session("pc"))
        val vm = DevicesViewModel(repo)
        advanceUntilIdle()
        vm.effects.test {
            vm.revoke(vm.state.value.items.first { it.familyId == "me" })
            vm.revoke(vm.state.value.items.first { it.familyId == "pc" })
            val msg = awaitItem() as SecurityEffect.Message
            assertThat((msg.text as UiText.Res).id).isEqualTo(R.string.devices_msg_revoked)
        }
        advanceUntilIdle()
        assertThat(repo.revokedSessions).containsExactly("pc")
        assertThat(vm.state.value.items.first { it.familyId == "pc" }.active).isFalse()
    }

    @Test
    fun `a new app password is shown once and the list refreshes`() = runTest {
        signIn(admin = false)
        val vm = AppPasswordsViewModel(repo, testConfig, store)
        advanceUntilIdle()
        assertThat(vm.davUrl).isEqualTo("https://drive.example/dav")
        assertThat(vm.username).isEqualTo("alice")
        assertThat(vm.create("   ")).isFalse()
        assertThat(vm.create(" NAS ")).isTrue()
        advanceUntilIdle()
        assertThat(vm.state.value.created).isEqualTo("secret-NAS")
        assertThat(vm.state.value.items.map { it.name }).containsExactly("NAS")
        vm.dismissCreated()
        assertThat(vm.state.value.created).isNull()

        vm.revoke(vm.state.value.items.single())
        advanceUntilIdle()
        assertThat(vm.state.value.items).isEmpty()
    }

    @Test
    fun `api keys are created with scopes in option order, only admins can switch to admin scopes`() = runTest {
        signIn(admin = false)
        val vm = AgentViewModel(repo, testConfig, store)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.endpoint).isEqualTo("https://drive.example/mcp")

        vm.setAdminKey(true)
        assertThat(vm.state.value.adminKey).isFalse()

        vm.toggleScope("chat:write")
        vm.toggleScope("files:upload")
        assertThat(vm.createKey("CI")).isTrue()
        advanceUntilIdle()
        assertThat(repo.createdKeys.single()).isEqualTo("CI" to listOf("files:read", "files:upload", "chat:write"))
        assertThat(vm.state.value.createdToken).isEqualTo("gpk_secret")
        assertThat(vm.state.value.keys.map { it.name }).containsExactly("CI")

        // 一个权限都不选不能创建
        listOf("files:read", "files:upload", "chat:write").forEach(vm::toggleScope)
        assertThat(vm.createKey("empty")).isFalse()
    }

    @Test
    fun `admins switching to an admin key reset the scopes to admin read`() = runTest {
        signIn(admin = true)
        val vm = AgentViewModel(repo, testConfig, store)
        advanceUntilIdle()
        vm.setAdminKey(true)
        assertThat(vm.state.value.scopes).containsExactly("admin:read")
        assertThat(vm.adminEndpoint).isEqualTo("https://drive.example/mcp/admin")
    }

    @Test
    fun `access roots take a path, empty clears the scope, an error stays on the dialog`() = runTest {
        signIn(admin = false)
        repo.keys = mutableListOf(ApiKey("k1", "CI", listOf("files:read"), "t", null))
        val vm = AgentViewModel(repo, testConfig, store)
        advanceUntilIdle()

        vm.editRoot("k1", CredentialType.API_KEY, "CI")
        vm.saveRoot("AI工作区")
        advanceUntilIdle()
        assertThat(repo.rootCalls.last()).isEqualTo(Triple("k1", CredentialType.API_KEY, "/AI工作区"))
        assertThat(vm.state.value.rootEdit).isNull()
        assertThat(vm.state.value.rootName("k1", CredentialType.API_KEY)).isEqualTo("AI工作区")

        vm.editRoot("k1", CredentialType.API_KEY, "CI")
        vm.saveRoot("   ")
        advanceUntilIdle()
        assertThat(repo.rootCalls.last().third).isNull()
        assertThat(vm.state.value.rootName("k1", CredentialType.API_KEY)).isNull()

        repo.rootError = GopanException(GopanErrors.INVALID_INPUT, "请选择实际文件夹")
        vm.editRoot("k1", CredentialType.API_KEY, "CI")
        vm.saveRoot("/nope")
        advanceUntilIdle()
        val edit = vm.state.value.rootEdit
        assertThat(edit?.error).isEqualTo("请选择实际文件夹")
        assertThat(edit?.errorPath).isEqualTo("/nope")
        assertThat(edit?.saving).isFalse()
    }

    @Test
    fun `audit loads lazily and pages back until a short page`() = runTest {
        signIn(admin = false)
        repo.auditPages = ArrayDeque(listOf((100L downTo 51L).map(::audit), (50L downTo 41L).map(::audit)))
        val vm = AgentViewModel(repo, testConfig, store)
        advanceUntilIdle()
        assertThat(repo.auditCalls).isEmpty()

        vm.selectTab(AgentTab.AUDIT)
        advanceUntilIdle()
        assertThat(vm.state.value.audit).hasSize(50)
        assertThat(vm.state.value.auditEnd).isFalse()

        vm.loadAudit(reset = false)
        advanceUntilIdle()
        assertThat(repo.auditCalls).containsExactly(null, 51L).inOrder()
        assertThat(vm.state.value.audit).hasSize(60)
        assertThat(vm.state.value.auditEnd).isTrue()

        vm.loadAudit(reset = false)
        advanceUntilIdle()
        assertThat(repo.auditCalls).hasSize(2)
    }

    @Test
    fun `revoking a grant removes it`() = runTest {
        signIn(admin = false)
        repo.grants = mutableListOf(OAuthGrant("g1", "Claude", listOf("files:read"), "t", "t"))
        val vm = AgentViewModel(repo, testConfig, store)
        advanceUntilIdle()
        vm.revokeGrant(vm.state.value.grants.single())
        advanceUntilIdle()
        assertThat(vm.state.value.grants).isEmpty()
    }

    @Test
    fun `scope labels match the web`() {
        assertThat(McpScopes.label("files:read")).isEqualTo("浏览与搜索")
        assertThat(McpScopes.label("admin:purge")).isEqualTo("永久删除回收站文件")
        assertThat(McpScopes.label("unknown:x")).isEqualTo("unknown:x")
        assertThat(McpScopes.sorted(listOf("x:y", "files:download", "chat:read", "files:read")))
            .containsExactly("files:read", "files:download", "chat:read", "x:y").inOrder()
    }
}
