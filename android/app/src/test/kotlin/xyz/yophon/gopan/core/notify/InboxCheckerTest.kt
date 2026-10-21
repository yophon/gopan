package xyz.yophon.gopan.core.notify

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.FakeNotificationsRepository
import xyz.yophon.gopan.core.data.notifications.FakeNotificationsRepository.Companion.notification
import xyz.yophon.gopan.feature.drive.InMemoryPreferencesDataStore

/** 一次检查:取未读第一页 → 挑新的 → 发 → 记游标(存在 DataStore 里) */
class InboxCheckerTest {

    private class RecordingPoster(var allowed: Boolean = true) : InboxPoster {
        val posted = mutableListOf<Pair<List<String>, Int>>()
        var cleared = 0

        override fun canPost(): Boolean = allowed

        override fun post(items: List<AppNotification>, overflow: Int) {
            posted += items.map { it.id } to overflow
        }

        override fun clearPosted() {
            cleared++
        }
    }

    private val repo = FakeNotificationsRepository()
    private val prefs = InboxPrefs(InMemoryPreferencesDataStore())
    private val session = SessionStore()
    private val poster = RecordingPoster()
    private val checker = InboxChecker(repo, prefs, session, poster).apply { foreground = { false } }

    private fun signIn(id: String = "u1") = session.setSignedIn("t", UserSummary(id, "alice", 0, 0, false))

    @Test
    fun `first check only records, later checks post what is new`() = runTest {
        signIn()
        repo.items = listOf(notification("01a", "2026-10-21T10:00:00Z"))
        assertThat(checker.check()).isEqualTo(0)
        assertThat(poster.posted).isEmpty()
        assertThat(prefs.cursor()?.lastId).isEqualTo("01a")

        repo.items = listOf(notification("01b", "2026-10-21T10:05:00Z")) + repo.items
        assertThat(checker.check()).isEqualTo(1)
        assertThat(poster.posted.single()).isEqualTo(listOf("01b") to 0)
        assertThat(prefs.cursor()?.lastId).isEqualTo("01b")
        assertThat(repo.calls.last()).isEqualTo("list(null,true)")

        // 同一批再查一次不会重复发
        assertThat(checker.check()).isEqualTo(0)
        assertThat(poster.posted).hasSize(1)
    }

    @Test
    fun `turned off or signed out does nothing`() = runTest {
        session.setSignedOut()
        assertThat(checker.check()).isEqualTo(0)
        assertThat(repo.calls).isEmpty()

        signIn()
        prefs.setSystemAlerts(false)
        assertThat(prefs.systemAlerts.first()).isFalse()
        assertThat(checker.check()).isEqualTo(0)
        assertThat(repo.calls).isEmpty()
    }

    @Test
    fun `in the foreground or without permission the cursor still advances`() = runTest {
        signIn()
        prefs.saveCursor(InboxCursor("u1", "", ""))
        repo.items = listOf(notification("01a"))
        checker.foreground = { true }
        assertThat(checker.check()).isEqualTo(0)
        assertThat(prefs.cursor()?.lastId).isEqualTo("01a")

        checker.foreground = { false }
        poster.allowed = false
        repo.items = listOf(notification("01b", "2026-10-21T11:00:00Z")) + repo.items
        assertThat(checker.check()).isEqualTo(0)
        assertThat(prefs.cursor()?.lastId).isEqualTo("01b")
        assertThat(poster.posted).isEmpty()
    }

    @Test
    fun `network failure keeps the cursor for the next round`() = runTest {
        signIn()
        prefs.saveCursor(InboxCursor("u1", "01a", "2026-10-21T10:00:00Z"))
        repo.items = listOf(notification("01b", "2026-10-21T10:05:00Z"))
        repo.failNext = true
        assertThat(checker.check()).isEqualTo(0)
        assertThat(prefs.cursor()?.lastId).isEqualTo("01a")
        assertThat(checker.check()).isEqualTo(1)
    }

    @Test
    fun `overflow becomes one extra notification`() = runTest {
        signIn()
        prefs.saveCursor(InboxCursor("u1", "", ""))
        repo.items = (1..7).map { notification("01$it", "2026-10-21T10:0$it:00Z") }.reversed()
        assertThat(checker.check()).isEqualTo(InboxLogic.MAX_POSTED + 1)
        assertThat(poster.posted.single().second).isEqualTo(2)
    }

    @Test
    fun `clearing the cursor (sign-out) starts over without a backlog`() = runTest {
        signIn()
        prefs.saveCursor(InboxCursor("u1", "01a", "2026-10-21T10:00:00Z"))
        prefs.clearCursor()
        repo.items = listOf(notification("01b", "2026-10-21T10:05:00Z"))
        assertThat(checker.check()).isEqualTo(0)
        assertThat(prefs.cursor()).isEqualTo(InboxCursor("u1", "01b", "2026-10-21T10:05:00Z"))
    }
}
