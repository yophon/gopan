package xyz.yophon.gopan.feature.collect

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.collect.FileRequest
import xyz.yophon.gopan.core.data.collect.FileRequestForm
import xyz.yophon.gopan.core.data.collect.FileRequestUpload
import xyz.yophon.gopan.core.data.collect.FileRequestUploadPage
import xyz.yophon.gopan.core.data.collect.FileRequestsRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.drive.DRIVE_ACTIONS
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.NodeAction
import xyz.yophon.gopan.feature.drive.SEARCH_ACTIONS
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.drive.appliesTo
import xyz.yophon.gopan.feature.shares.testConfig
import java.time.Instant

private const val GB = 1024L * 1024 * 1024
private const val MB = 1024L * 1024

class FakeFileRequestsRepository : FileRequestsRepository {
    var requests = mutableListOf<FileRequest>()
    var failList: GopanException? = null
    var failRevoke: GopanException? = null
    var failSave: GopanException? = null
    var failMarkSeen: GopanException? = null
    val revoked = mutableListOf<String>()
    val marked = mutableListOf<String>()
    val created = mutableListOf<Triple<String, FileRequestForm, String?>>()
    val updated = mutableListOf<Pair<String, FileRequestForm>>()

    /** 收件记录:cursor(null = 第一页)→ 那一页;没配的 cursor 抛 NOT_FOUND */
    val pages = mutableMapOf<String?, FileRequestUploadPage>()
    val uploadCalls = mutableListOf<Pair<String, String?>>()
    var failUploads: GopanException? = null

    /** 非空时 uploads 挂起到它完成(测「正在翻页时不重复拉」) */
    var uploadsGate: CompletableDeferred<Unit>? = null

    override suspend fun myRequests(): List<FileRequest> {
        failList?.let { throw it }
        return requests.toList()
    }

    override suspend fun create(folderId: String, form: FileRequestForm, password: String?): FileRequest {
        failSave?.let { throw it }
        created += Triple(folderId, form, password)
        return request("new", title = form.title, hasPassword = password != null, expiresAt = form.expiresAt?.toString())
    }

    override suspend fun update(id: String, form: FileRequestForm): FileRequest {
        failSave?.let { throw it }
        updated += id to form
        return request(id, title = form.title, expiresAt = form.expiresAt?.toString(), fileCount = 2)
    }

    override suspend fun revoke(id: String) {
        failRevoke?.let { throw it }
        revoked += id
    }

    override suspend fun markSeen(id: String) {
        marked += id
        failMarkSeen?.let { throw it }
    }

    override suspend fun uploads(id: String, cursor: String?): FileRequestUploadPage {
        uploadCalls += id to cursor
        uploadsGate?.await()
        failUploads?.let { throw it }
        return pages[cursor] ?: throw GopanException(GopanErrors.NOT_FOUND, "没有这一页")
    }
}

fun request(
    id: String,
    title: String = "收作业 $id",
    hasPassword: Boolean = false,
    expiresAt: String? = null,
    createdAt: String = "2026-10-14T00:00:00Z",
    fileCount: Int = 0,
    totalBytes: Long = 0,
    uploaderCount: Int = 0,
    newCount: Int = 0,
    maxFileSize: Long? = null,
    maxTotalBytes: Long? = 10 * GB,
    maxFiles: Int? = null,
    requireName: Boolean = false,
    groupByUploader: Boolean = false,
) = FileRequest(
    id = id,
    token = "tok-$id",
    folderId = "f-$id",
    folderName = "作业",
    title = title,
    hasPassword = hasPassword,
    requireName = requireName || groupByUploader,
    groupByUploader = groupByUploader,
    maxFileSize = maxFileSize,
    maxTotalBytes = maxTotalBytes,
    maxFiles = maxFiles,
    expiresAt = expiresAt,
    createdAt = createdAt,
    fileCount = fileCount,
    totalBytes = totalBytes,
    uploaderCount = uploaderCount,
    newCount = newCount,
)

fun upload(id: String, name: String = "$id.jpg", uploader: String = "小明", nodeId: String? = "n-$id", parentId: String? = "f-1") =
    FileRequestUpload(
        id = id,
        fileName = name,
        size = 2048,
        uploaderName = uploader,
        ip = "1.2.3.4",
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1",
        createdAt = "2026-10-15T08:00:00Z",
        nodeId = nodeId,
        parentId = parentId,
    )

private fun UiText.res(): Pair<Int, List<Any>> = (this as UiText.Res).let { it.id to it.formatArgs.toList() }

class FileRequestTextsTest {

    @Test
    fun `received line counts files, bytes and people, or says nothing yet`() {
        assertThat(FileRequestTexts.received(request("a")).res()).isEqualTo(R.string.collect_received_none to emptyList<Any>())
        assertThat(FileRequestTexts.received(request("b", fileCount = 3, totalBytes = 1536, uploaderCount = 2)).res())
            .isEqualTo(R.string.collect_received to listOf(3, "1.5 KB", 2))
    }

    @Test
    fun `deadline line distinguishes open, closed and no deadline`() {
        val now = Instant.parse("2026-10-14T12:00:00Z")
        assertThat(FileRequestTexts.deadline(request("a"), now).res().first).isEqualTo(R.string.collect_no_deadline)
        val future = "2026-10-21T12:00:00Z"
        assertThat(FileRequestTexts.deadline(request("b", expiresAt = future), now).res())
            .isEqualTo(R.string.collect_until to listOf(formatTime(future)))
        val past = "2026-10-13T12:00:00Z"
        assertThat(FileRequestTexts.deadline(request("c", expiresAt = past), now).res())
            .isEqualTo(R.string.collect_closed_at to listOf(formatTime(past)))
        assertThat(request("c", expiresAt = past).isExpired(now)).isTrue()
        assertThat(request("b", expiresAt = future).isExpired(now)).isFalse()
    }

    @Test
    fun `details list the naming mode and every limit that is set`() {
        assertThat(FileRequestTexts.details(request("a", maxTotalBytes = null))).isEmpty()
        val all = FileRequestTexts.details(request("b", groupByUploader = true, maxFiles = 30, maxTotalBytes = GB, maxFileSize = 100 * MB))
        assertThat(all.map { it.res() }).containsExactly(
            R.string.collect_flag_group to emptyList<Any>(),
            R.string.collect_limit_files to listOf(30),
            R.string.collect_limit_total to listOf("1.0 GB"),
            R.string.collect_limit_file_size to listOf("100.0 MB"),
        ).inOrder()
        assertThat(FileRequestTexts.details(request("c", requireName = true, maxTotalBytes = null)).map { it.res().first })
            .containsExactly(R.string.collect_flag_require_name)
    }

    @Test
    fun `limit labels and presets match the web dialog`() {
        assertThat(CollectLimits.label(100 * MB)).isEqualTo("100 MB")
        assertThat(CollectLimits.label(GB)).isEqualTo("1 GB")
        assertThat(CollectLimits.label(5 * GB)).isEqualTo("5 GB")
        assertThat(CollectLimits.label(GB + GB / 2)).isEqualTo("1.5 GB")
        assertThat(CollectLimits.label(GB + GB / 3)).isEqualTo("1.33 GB")
        assertThat(CollectLimits.label(MB / 2)).isEqualTo("0.5 MB")
        assertThat(CollectLimits.FILE_SIZE_PRESETS).containsExactly(null, 100 * MB, GB, 5 * GB).inOrder()
        assertThat(CollectLimits.TOTAL_PRESETS).containsExactly(null, GB, 10 * GB, 50 * GB).inOrder()
        // 预设里没有的现值补在后面;已在预设里、或不限,原样
        assertThat(CollectLimits.withCurrent(CollectLimits.TOTAL_PRESETS, 3 * GB)).containsExactly(null, GB, 10 * GB, 50 * GB, 3 * GB).inOrder()
        assertThat(CollectLimits.withCurrent(CollectLimits.TOTAL_PRESETS, GB)).isEqualTo(CollectLimits.TOTAL_PRESETS)
        assertThat(CollectLimits.withCurrent(CollectLimits.TOTAL_PRESETS, null)).isEqualTo(CollectLimits.TOTAL_PRESETS)
    }

    @Test
    fun `the visitor link is base slash c slash token`() {
        assertThat(testConfig.collectUrl("Ab_9-x")).isEqualTo("https://drive.example/c/Ab_9-x")
    }

    @Test
    fun `collect files is offered for folders only, on the drive and search sheets`() {
        val folder = Node(id = "f", name = "作业", kind = NodeKind.FOLDER)
        val file = Node(id = "x", name = "a.pdf", kind = NodeKind.FILE, size = 1)
        assertThat(NodeAction.COLLECT.appliesTo(folder)).isTrue()
        assertThat(NodeAction.COLLECT.appliesTo(file)).isFalse()
        assertThat(DRIVE_ACTIONS).contains(NodeAction.COLLECT)
        assertThat(SEARCH_ACTIONS).contains(NodeAction.COLLECT)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileRequestsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeFileRequestsRepository()

    @Test
    fun `lists links newest first with the visitor link and the text to send`() = runTest {
        repo.requests = mutableListOf(request("a", createdAt = "2026-10-01T00:00:00Z"), request("b", createdAt = "2026-10-12T00:00:00Z"))
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.status).isEqualTo(ListStatus.Content)
        assertThat(s.items.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(vm.url(s.items.first())).isEqualTo("https://drive.example/c/tok-b")
        // 列表里不知道密码:只有标题和链接
        assertThat(vm.sendText(s.items.first())).isEqualTo("收作业 b\n链接:https://drive.example/c/tok-b")
    }

    @Test
    fun `ending a link removes it with the web message, and one already gone counts as ended`() = runTest {
        repo.requests = mutableListOf(request("a"), request("b"))
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()

        vm.effects.test {
            vm.revoke(vm.state.value.items.first { it.id == "a" })
            assertThat(((awaitItem() as FileRequestsEffect.Message).text as UiText.Res).id).isEqualTo(R.string.collect_msg_revoked)

            repo.failRevoke = GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
            vm.revoke(vm.state.value.items.first { it.id == "b" })
            assertThat(((awaitItem() as FileRequestsEffect.Message).text as UiText.Res).id).isEqualTo(R.string.collect_msg_revoked)
        }
        assertThat(vm.state.value.items).isEmpty()
        assertThat(repo.revoked).containsExactly("a")
    }

    @Test
    fun `a failed revoke keeps the link and says why`() = runTest {
        repo.requests = mutableListOf(request("a"))
        repo.failRevoke = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()
        vm.effects.test {
            vm.revoke(vm.state.value.items.single())
            assertThat(vm.state.value.revoking).containsExactly("a")
            assertThat((awaitItem() as FileRequestsEffect.Message).text).isEqualTo(UiText.Plain("网络连接失败"))
        }
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.revoking).isEmpty()
    }

    @Test
    fun `a failed refresh keeps the list, the first load failing shows an error page`() = runTest {
        repo.failList = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Error("网络连接失败"))

        repo.failList = null
        repo.requests = mutableListOf(request("a"))
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.items).hasSize(1)

        repo.failList = GopanException(GopanErrors.NETWORK, "网络连接失败")
        vm.effects.test {
            vm.refresh()
            assertThat((awaitItem() as FileRequestsEffect.Message).text).isEqualTo(UiText.Plain("网络连接失败"))
        }
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).hasSize(1)
        assertThat(vm.state.value.refreshing).isFalse()
    }

    @Test
    fun `coming back refreshes silently and picks up new uploads`() = runTest {
        repo.requests = mutableListOf(request("a"))
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()
        repo.requests = mutableListOf(request("a", fileCount = 1, totalBytes = 10, uploaderCount = 1, newCount = 1))
        vm.onResumed()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        advanceUntilIdle()
        assertThat(vm.state.value.items.single().newCount).isEqualTo(1)
    }

    @Test
    fun `opening the records clears the new badge, and a saved edit replaces the row`() = runTest {
        repo.requests = mutableListOf(request("a", newCount = 4), request("b", newCount = 1, createdAt = "2026-10-01T00:00:00Z"))
        val vm = FileRequestsViewModel(repo, testConfig, ScreenCache(SessionStore()))
        advanceUntilIdle()
        vm.openedUploads(vm.state.value.items.first { it.id == "a" })
        assertThat(vm.state.value.items.map { it.newCount }).containsExactly(0, 1).inOrder()

        vm.upsert(request("b", title = "改过的标题", createdAt = "2026-10-01T00:00:00Z"))
        assertThat(vm.state.value.items.map { it.title }).containsExactly("收作业 a", "改过的标题").inOrder()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileRequestEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeFileRequestsRepository()
    private val now = Instant.parse("2026-10-14T00:00:00Z")
    private fun creating() = FileRequestEditViewModel(FileRequestTarget.Create("f1", "期末作业"), repo, testConfig)

    @Test
    fun `new links default to the folder title, 7 days, 10 GB total, no other limits, no password`() = runTest {
        val vm = creating()
        val s = vm.state.value
        assertThat(s.editing).isFalse()
        assertThat(s.title).isEqualTo("往「期末作业」传文件")
        assertThat(s.expiry).isEqualTo(CollectExpiry.DAYS_7)
        assertThat(s.maxTotalBytes).isEqualTo(10 * GB)

        vm.save(now)
        advanceUntilIdle()
        val (folderId, form, password) = repo.created.single()
        assertThat(folderId).isEqualTo("f1")
        assertThat(form).isEqualTo(
            FileRequestForm(
                title = "往「期末作业」传文件",
                note = "",
                requireName = false,
                groupByUploader = false,
                maxFileSize = null,
                maxTotalBytes = 10 * GB,
                maxFiles = null,
                expiresAt = Instant.parse("2026-10-21T00:00:00Z"),
            ),
        )
        assertThat(password).isNull()
        assertThat(vm.state.value.url).isEqualTo("https://drive.example/c/tok-new")
        assertThat(vm.state.value.created?.hasPassword).isFalse()
    }

    @Test
    fun `expiry choices become instants from now, unlimited becomes null`() {
        val base = FileRequestEditState(title = "t")
        fun at(e: CollectExpiry) = FileRequestEditViewModel.buildForm(base.copy(expiry = e), null, now).expiresAt
        assertThat(at(CollectExpiry.DAY_1)).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"))
        assertThat(at(CollectExpiry.DAYS_7)).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"))
        assertThat(at(CollectExpiry.DAYS_30)).isEqualTo(Instant.parse("2026-11-13T00:00:00Z"))
        assertThat(at(CollectExpiry.UNLIMITED)).isNull()
        assertThat(CollectExpiry.CREATE_OPTIONS).doesNotContain(CollectExpiry.KEEP)
        assertThat(CollectExpiry.EDIT_OPTIONS.first()).isEqualTo(CollectExpiry.KEEP)
    }

    @Test
    fun `limit presets go out as bytes, unlimited as null, and the count is parsed`() = runTest {
        val vm = creating()
        vm.setMaxFileSize(GB)
        vm.setMaxTotalBytes(null)
        vm.setMaxFiles("3 0个")
        assertThat(vm.state.value.maxFiles).isEqualTo("30")
        vm.setTitle("  收照片  ")
        vm.setNote("  按姓名命名  ")
        vm.save(now)
        advanceUntilIdle()
        val form = repo.created.single().second
        assertThat(form.maxFileSize).isEqualTo(GB)
        assertThat(form.maxTotalBytes).isNull()
        assertThat(form.maxFiles).isEqualTo(30)
        assertThat(form.title).isEqualTo("收照片")
        assertThat(form.note).isEqualTo("按姓名命名")
    }

    @Test
    fun `grouping by uploader forces require name on and keeps it there`() = runTest {
        val vm = creating()
        vm.setGroupByUploader(true)
        assertThat(vm.state.value.effectiveRequireName).isTrue()
        // 开着按人分文件夹时关不掉「要求留名」
        vm.setRequireName(false)
        assertThat(vm.state.value.effectiveRequireName).isTrue()
        vm.save(now)
        advanceUntilIdle()
        val form = repo.created.single().second
        assertThat(form.groupByUploader).isTrue()
        assertThat(form.requireName).isTrue()

        // 关掉分文件夹后,留名保持打开(与网页端一致),可以再单独关
        val again = creating()
        again.setGroupByUploader(true)
        again.setGroupByUploader(false)
        assertThat(again.state.value.requireName).isTrue()
        again.setRequireName(false)
        assertThat(again.state.value.effectiveRequireName).isFalse()
    }

    @Test
    fun `the password is a random code by default and only sent when protection is on`() = runTest {
        val vm = creating()
        vm.setUsePassword(true)
        val code = vm.state.value.password
        assertThat(code).matches("[a-z2-9]{4}")
        vm.setUsePassword(false)
        vm.save(now)
        advanceUntilIdle()
        assertThat(repo.created.single().third).isNull()

        val withPw = creating()
        withPw.setUsePassword(true)
        withPw.setPassword(" ab12 ")
        withPw.save(now)
        advanceUntilIdle()
        assertThat(repo.created.last().third).isEqualTo("ab12")
        assertThat(withPw.state.value.created?.hasPassword).isTrue()
        assertThat(withPw.state.value.password).isEqualTo("ab12")
        val url = withPw.state.value.url!!
        assertThat(FileRequestEditViewModel.shareText(withPw.state.value.created!!.title, url, withPw.state.value.password))
            .isEqualTo("往「期末作业」传文件\n链接:https://drive.example/c/tok-new\n密码:ab12")
    }

    @Test
    fun `bad input is refused locally`() = runTest {
        val vm = creating()
        vm.setTitle("   ")
        vm.save(now)
        assertThat(vm.state.value.error).isEqualTo("请填写标题")

        vm.setTitle("t")
        vm.setMaxFiles("0")
        vm.save(now)
        assertThat(vm.state.value.error).contains("个数上限")
        vm.setMaxFiles("100001")
        vm.save(now)
        assertThat(vm.state.value.error).contains("个数上限")

        vm.setMaxFiles("")
        vm.setUsePassword(true)
        vm.setPassword("  ")
        vm.save(now)
        assertThat(vm.state.value.error).isEqualTo("请输入密码,或关闭密码保护")
        advanceUntilIdle()
        assertThat(repo.created).isEmpty()

        // 输入上限
        vm.setTitle("x".repeat(150))
        assertThat(vm.state.value.title).hasLength(CollectLimits.MAX_TITLE)
        vm.setNote("y".repeat(1200))
        assertThat(vm.state.value.note).hasLength(CollectLimits.MAX_NOTE)
    }

    @Test
    fun `a server error stays in the dialog`() = runTest {
        repo.failSave = GopanException(GopanErrors.NOT_A_FOLDER, "目标不是可用的文件夹")
        val vm = creating()
        vm.save(now)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo("目标不是可用的文件夹")
        assertThat(vm.state.value.saving).isFalse()
        assertThat(vm.state.value.created).isNull()
    }

    @Test
    fun `editing prefills the form, keeps the deadline by default and an odd limit as its own option`() = runTest {
        val original = request(
            "r1",
            title = "收照片",
            expiresAt = "2026-10-20T08:30:00Z",
            maxTotalBytes = 3 * GB,
            maxFileSize = 100 * MB,
            maxFiles = 50,
            requireName = true,
        )
        val vm = FileRequestEditViewModel(FileRequestTarget.Edit(original), repo, testConfig)
        val s = vm.state.value
        assertThat(s.editing).isTrue()
        assertThat(s.expiry).isEqualTo(CollectExpiry.KEEP)
        assertThat(s.title).isEqualTo("收照片")
        assertThat(s.maxFiles).isEqualTo("50")
        assertThat(s.totalChoices).containsExactly(null, GB, 10 * GB, 50 * GB, 3 * GB).inOrder()
        assertThat(s.fileSizeChoices).isEqualTo(CollectLimits.FILE_SIZE_PRESETS)

        vm.save(now)
        advanceUntilIdle()
        val (id, form) = repo.updated.single()
        assertThat(id).isEqualTo("r1")
        assertThat(form.expiresAt).isEqualTo(Instant.parse("2026-10-20T08:30:00Z"))
        assertThat(form.maxTotalBytes).isEqualTo(3 * GB)
        assertThat(form.maxFileSize).isEqualTo(100 * MB)
        assertThat(form.maxFiles).isEqualTo(50)
        assertThat(form.requireName).isTrue()
        assertThat(repo.created).isEmpty()
        // 保存好了:界面据此关对话框,拿到的是服务端回显
        assertThat(vm.state.value.saved?.fileCount).isEqualTo(2)
        assertThat(vm.state.value.created).isNull()
    }

    @Test
    fun `editing can change or drop the deadline, and keep means none when there was none`() = runTest {
        val vm = FileRequestEditViewModel(FileRequestTarget.Edit(request("r1", expiresAt = "2026-10-20T08:30:00Z")), repo, testConfig)
        vm.setExpiry(CollectExpiry.UNLIMITED)
        vm.save(now)
        advanceUntilIdle()
        assertThat(repo.updated.single().second.expiresAt).isNull()

        val vm2 = FileRequestEditViewModel(FileRequestTarget.Edit(request("r2", expiresAt = "2026-10-20T08:30:00Z")), repo, testConfig)
        vm2.setExpiry(CollectExpiry.DAYS_30)
        vm2.save(now)
        advanceUntilIdle()
        assertThat(repo.updated.last().second.expiresAt).isEqualTo(Instant.parse("2026-11-13T00:00:00Z"))

        val vm3 = FileRequestEditViewModel(FileRequestTarget.Edit(request("r3", expiresAt = null)), repo, testConfig)
        vm3.save(now)
        advanceUntilIdle()
        assertThat(repo.updated.last().second.expiresAt).isNull()
    }

    @Test
    fun `editing never sends a password`() = runTest {
        val vm = FileRequestEditViewModel(FileRequestTarget.Edit(request("r1", hasPassword = true)), repo, testConfig)
        vm.setUsePassword(true)
        vm.setPassword("")
        vm.save(now)
        advanceUntilIdle()
        // 修改时没有密码一栏:空密码不拦,也不会走新建
        assertThat(repo.updated).hasSize(1)
        assertThat(repo.created).isEmpty()
    }

    @Test
    fun `the text to send leaves out a password that is not known`() {
        assertThat(FileRequestEditViewModel.shareText("收作业", "https://d/c/t", null)).isEqualTo("收作业\n链接:https://d/c/t")
        assertThat(FileRequestEditViewModel.shareText("收作业", "https://d/c/t", "ab12")).isEqualTo("收作业\n链接:https://d/c/t\n密码:ab12")
        assertThat(FileRequestEditViewModel.shareText(" ", "https://d/c/t", null)).isEqualTo("链接:https://d/c/t")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileRequestUploadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeFileRequestsRepository()

    private fun vm(newCount: Int = 0, handle: SavedStateHandle = SavedStateHandle(mapOf("requestId" to "r1", "title" to "收作业", "newCount" to newCount))) =
        FileRequestUploadsViewModel(handle, repo, ScreenCache(SessionStore()))

    @Test
    fun `opening with new uploads marks them seen, without new ones it does not`() = runTest {
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u1")), nextCursor = null)
        val handle = SavedStateHandle(mapOf("requestId" to "r1", "title" to "收作业", "newCount" to 3))
        val first = vm(handle = handle)
        advanceUntilIdle()
        assertThat(repo.marked).containsExactly("r1")
        assertThat(first.title).isEqualTo("收作业")
        assertThat(first.state.value.items.map { it.id }).containsExactly("u1")

        // 旋转屏幕(同一个 SavedStateHandle)不重复标
        vm(handle = handle)
        advanceUntilIdle()
        assertThat(repo.marked).hasSize(1)

        vm(newCount = 0)
        advanceUntilIdle()
        assertThat(repo.marked).hasSize(1)
    }

    @Test
    fun `failing to mark seen does not stop the records from showing`() = runTest {
        repo.failMarkSeen = GopanException(GopanErrors.NETWORK, "网络连接失败")
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u1")), nextCursor = null)
        val vm = vm(newCount = 1)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).hasSize(1)
    }

    @Test
    fun `pages follow the cursor until there is no next one`() = runTest {
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u3"), upload("u2")), nextCursor = "c2")
        repo.pages["c2"] = FileRequestUploadPage(listOf(upload("u2"), upload("u1")), nextCursor = null)
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.state.value.nextCursor).isEqualTo("c2")

        vm.loadMore()
        assertThat(vm.state.value.loadingMore).isTrue()
        vm.loadMore() // 正在拉:不重复
        advanceUntilIdle()
        assertThat(repo.uploadCalls).containsExactly("r1" to null, "r1" to "c2").inOrder()
        // 两页交界处重复的那条只留一份
        assertThat(vm.state.value.items.map { it.id }).containsExactly("u3", "u2", "u1").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()
        assertThat(vm.state.value.loadingMore).isFalse()

        vm.loadMore() // 到底了
        advanceUntilIdle()
        assertThat(repo.uploadCalls).hasSize(2)
    }

    @Test
    fun `no paging before the first page has arrived`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.uploadsGate = gate
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u1")), nextCursor = "c2")
        val vm = vm()
        advanceUntilIdle()
        vm.loadMore()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(repo.uploadCalls).containsExactly("r1" to null)
    }

    @Test
    fun `a failed page stays in the footer and retrying picks up where it left off`() = runTest {
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u2")), nextCursor = "c2")
        val vm = vm()
        advanceUntilIdle()
        repo.failUploads = GopanException(GopanErrors.NETWORK, "网络连接失败")
        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.loadMoreError).isEqualTo("网络连接失败")
        assertThat(vm.state.value.items).hasSize(1)

        repo.failUploads = null
        repo.pages["c2"] = FileRequestUploadPage(listOf(upload("u1")), nextCursor = null)
        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.loadMoreError).isNull()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("u2", "u1").inOrder()
    }

    @Test
    fun `refreshing goes back to the newest page, a failed refresh keeps what is shown`() = runTest {
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u1")), nextCursor = null)
        val vm = vm()
        advanceUntilIdle()
        repo.pages[null] = FileRequestUploadPage(listOf(upload("u2"), upload("u1")), nextCursor = null)
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("u2", "u1").inOrder()
        assertThat(vm.state.value.refreshing).isFalse()

        repo.failUploads = GopanException(GopanErrors.NETWORK, "网络连接失败")
        vm.messages.test {
            vm.refresh()
            assertThat(awaitItem()).isEqualTo("网络连接失败")
        }
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).hasSize(2)
    }

    @Test
    fun `the first page failing shows an error page that can be retried`() = runTest {
        repo.failUploads = GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Error("对象不存在或已被删除"))
        repo.failUploads = null
        repo.pages[null] = FileRequestUploadPage(emptyList(), nextCursor = null)
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).isEmpty()
    }

    @Test
    fun `records keep the uploader name, fall back to unsigned, and know when the file is gone`() {
        assertThat(FileRequestUploadsViewModel.uploaderText(upload("a"))).isEqualTo(UiText.Plain("小明"))
        assertThat((FileRequestUploadsViewModel.uploaderText(upload("b", uploader = "  ")) as UiText.Res).id)
            .isEqualTo(R.string.collect_uploads_anonymous)
        assertThat(upload("c", nodeId = null, parentId = null).fileGone).isTrue()
        assertThat(upload("d", parentId = null).fileGone).isFalse()
    }
}
