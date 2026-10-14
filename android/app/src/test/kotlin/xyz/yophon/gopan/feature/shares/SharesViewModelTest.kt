package xyz.yophon.gopan.feature.shares

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.shares.Share
import xyz.yophon.gopan.core.data.shares.ShareVisit
import xyz.yophon.gopan.core.data.shares.SharesRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import java.time.Instant

class FakeSharesRepository : SharesRepository {
    var shares = mutableListOf<Share>()
    var failList: GopanException? = null
    var failRevoke: GopanException? = null
    val revoked = mutableListOf<String>()
    val created = mutableListOf<Triple<String, String?, Instant?>>()

    override suspend fun myShares(): List<Share> {
        failList?.let { throw it }
        return shares.toList()
    }

    override suspend fun create(nodeId: String, password: String?, expiresAt: Instant?): Share {
        created += Triple(nodeId, password, expiresAt)
        return share("new", nodeId, hasPassword = password != null, expiresAt = expiresAt?.toString())
    }

    override suspend fun revoke(id: String) {
        failRevoke?.let { throw it }
        revoked += id
    }

    override suspend fun visits(shareId: String, limit: Int): List<ShareVisit> =
        listOf(ShareVisit("verify", "1.2.3.4", "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile", "2026-10-14T01:00:00Z"))
}

fun share(id: String, nodeId: String = "n-$id", hasPassword: Boolean = false, expiresAt: String? = null, createdAt: String = "2026-10-14T00:00:00Z") =
    Share(id = id, token = "tok-$id", nodeId = nodeId, nodeName = "$nodeId.pdf", isFolder = false, hasPassword = hasPassword, expiresAt = expiresAt, createdAt = createdAt)

val testConfig = AppConfig(baseUrl = "https://drive.example/", idIssuer = "https://id.example", idClientId = "c", idRedirectUri = "x:/cb")

@OptIn(ExperimentalCoroutinesApi::class)
class SharesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeSharesRepository()

    @Test
    fun `lists shares newest first and builds the visitor link`() = runTest {
        repo.shares = mutableListOf(share("a", createdAt = "2026-10-01T00:00:00Z"), share("b", createdAt = "2026-10-12T00:00:00Z"))
        val vm = SharesViewModel(repo, testConfig)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.status).isEqualTo(ListStatus.Content)
        assertThat(s.items.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(vm.url(s.items.first())).isEqualTo("https://drive.example/s/tok-b")
    }

    @Test
    fun `revoking removes the share, and one already gone counts as revoked`() = runTest {
        repo.shares = mutableListOf(share("a"), share("b"))
        val vm = SharesViewModel(repo, testConfig)
        advanceUntilIdle()

        vm.effects.test {
            vm.revoke(vm.state.value.items.first { it.id == "a" })
            val ok = awaitItem() as SharesEffect.Message
            assertThat((ok.text as UiText.Res).id).isEqualTo(R.string.shares_msg_revoked)

            repo.failRevoke = GopanException(GopanErrors.NOT_FOUND, "分享不存在")
            vm.revoke(vm.state.value.items.first { it.id == "b" })
            awaitItem()
        }
        assertThat(vm.state.value.items).isEmpty()
        assertThat(repo.revoked).containsExactly("a")
    }

    @Test
    fun `a failed revoke keeps the share and says why`() = runTest {
        repo.shares = mutableListOf(share("a"))
        repo.failRevoke = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = SharesViewModel(repo, testConfig)
        advanceUntilIdle()
        vm.effects.test {
            vm.revoke(vm.state.value.items.single())
            assertThat((awaitItem() as SharesEffect.Message).text).isEqualTo(UiText.Plain("网络连接失败"))
        }
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.revoking).isEmpty()
    }

    @Test
    fun `a failed refresh keeps the list on screen`() = runTest {
        repo.shares = mutableListOf(share("a"))
        val vm = SharesViewModel(repo, testConfig)
        advanceUntilIdle()
        repo.failList = GopanException(GopanErrors.NETWORK, "网络连接失败")
        vm.effects.test {
            vm.refresh()
            assertThat((awaitItem() as SharesEffect.Message).text).isEqualTo(UiText.Plain("网络连接失败"))
        }
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).hasSize(1)
        assertThat(vm.state.value.refreshing).isFalse()
    }

    @Test
    fun `the first load failing shows an error page`() = runTest {
        repo.failList = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = SharesViewModel(repo, testConfig)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Error("网络连接失败"))
    }

    @Test
    fun `expired shares are detected`() {
        val now = Instant.parse("2026-10-14T12:00:00Z")
        assertThat(share("a", expiresAt = "2026-10-14T11:00:00Z").isExpired(now)).isTrue()
        assertThat(share("b", expiresAt = "2026-10-15T11:00:00Z").isExpired(now)).isFalse()
        assertThat(share("c").isExpired(now)).isFalse()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareCreateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeSharesRepository()

    @Test
    fun `creates with the chosen expiry and no password by default`() = runTest {
        val vm = ShareCreateViewModel(repo, testConfig)
        val now = Instant.parse("2026-10-14T00:00:00Z")
        vm.setExpiry(ShareExpiry.DAYS_30)
        vm.create("n1", now)
        advanceUntilIdle()
        assertThat(repo.created.single()).isEqualTo(Triple("n1", null, Instant.parse("2026-11-13T00:00:00Z")))
        val s = vm.state.value
        assertThat(s.url).isEqualTo("https://drive.example/s/tok-new")
        assertThat(s.created?.hasPassword).isFalse()
    }

    @Test
    fun `forever means no expiry`() = runTest {
        val vm = ShareCreateViewModel(repo, testConfig)
        vm.setExpiry(ShareExpiry.FOREVER)
        vm.create("n1")
        advanceUntilIdle()
        assertThat(repo.created.single().third).isNull()
    }

    @Test
    fun `turning on the password fills a random code that can be edited, and an empty one is refused`() = runTest {
        val vm = ShareCreateViewModel(repo, testConfig)
        vm.setUsePassword(true)
        val code = vm.state.value.password
        assertThat(code).matches("[a-z2-9]{4}")

        vm.setPassword("  ")
        vm.create("n1")
        advanceUntilIdle()
        assertThat(vm.state.value.error).isNotNull()
        assertThat(repo.created).isEmpty()

        vm.setPassword("x".repeat(40))
        assertThat(vm.state.value.password).hasLength(ShareCreateViewModel.MAX_PASSWORD)
        vm.setPassword(" abcd ")
        vm.create("n1")
        advanceUntilIdle()
        assertThat(repo.created.single().second).isEqualTo("abcd")
        assertThat(vm.state.value.created?.hasPassword).isTrue()
    }

    @Test
    fun `the text to send includes the password only when there is one`() {
        assertThat(ShareCreateViewModel.shareText("https://d/s/t", null)).isEqualTo("https://d/s/t")
        assertThat(ShareCreateViewModel.shareText("https://d/s/t", "ab12")).isEqualTo("链接:https://d/s/t\n密码:ab12")
    }
}
