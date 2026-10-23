package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary

class ScreenCacheTest {
    private val persisted = ScreenKey("t.list", ListSerializer(String.serializer()))
    private val memoryOnly = ScreenKey<List<String>>("t.memory")
    private val disk = InMemorySnapshotDisk()
    private val session = SessionStore().apply { signIn("u1") }

    private fun SessionStore.signIn(id: String) = setSignedIn("t", UserSummary(id, "user-$id", 0, 0, false))

    @Test
    fun `persisted keys survive into a fresh cache, as after a cold start`() = runTest {
        ScreenCache(session, disk).put(persisted, listOf("a", "b"))

        val fresh = ScreenCache(session, disk)
        assertThat(fresh[persisted]).isNull()
        assertThat(fresh.load(persisted)).containsExactly("a", "b").inOrder()
        // 读回来之后就在内存里了,同步读也拿得到
        assertThat(fresh[persisted]).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `memory-only keys never touch the disk`() = runTest {
        val cache = ScreenCache(session, disk)
        cache.put(memoryOnly, listOf("a"))
        assertThat(disk.files).isEmpty()
        assertThat(cache.load(memoryOnly)).containsExactly("a")
        assertThat(ScreenCache(session, disk).load(memoryOnly)).isNull()
    }

    @Test
    fun `an unchanged value is not written again`() = runTest {
        val cache = ScreenCache(session, disk)
        cache.put(persisted, listOf("a"))
        cache.put(persisted, listOf("a"))
        assertThat(disk.writes).isEqualTo(1)
        cache.put(persisted, listOf("a", "b"))
        assertThat(disk.writes).isEqualTo(2)

        // 读回来的值原样写回(页面把缓存放进状态,mirror 又写回来)也不算变化
        val fresh = ScreenCache(session, disk)
        fresh.put(persisted, fresh.load(persisted)!!)
        assertThat(disk.writes).isEqualTo(2)
    }

    @Test
    fun `newer data written while the disk is being read wins`() = runTest {
        ScreenCache(session, disk).put(persisted, listOf("old"))
        val cache = ScreenCache(session, disk)
        disk.readGate = CompletableDeferred()
        val loading = async { cache.load(persisted) }
        runCurrent()
        cache.put(persisted, listOf("new"))
        disk.readGate!!.complete(Unit)
        assertThat(loading.await()).containsExactly("new")
        assertThat(cache[persisted]).containsExactly("new")
    }

    @Test
    fun `snapshots belong to one user and are wiped on sign out`() = runTest {
        val cache = ScreenCache(session, disk)
        cache.put(persisted, listOf("mine"))

        session.signIn("u2")
        assertThat(ScreenCache(session, disk).load(persisted)).isNull()

        cache.clear()
        assertThat(disk.files).isEmpty()
        session.signIn("u1")
        assertThat(ScreenCache(session, disk).load(persisted)).isNull()
    }

    @Test
    fun `a broken snapshot file reads as nothing`() = runTest {
        disk.files["u1/t.list"] = "{not json"
        assertThat(ScreenCache(session, disk).load(persisted)).isNull()
    }

    @Test
    fun `signed out, nothing is read or written`() = runTest {
        val signedOut = SessionStore()
        val cache = ScreenCache(signedOut, disk)
        cache.put(persisted, listOf("a"))
        assertThat(disk.files).isEmpty()
        assertThat(cache.load(persisted)).isNull()
    }
}
