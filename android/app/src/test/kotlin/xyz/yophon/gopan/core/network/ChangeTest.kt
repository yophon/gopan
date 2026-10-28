package xyz.yophon.gopan.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChangeTest {

    @Test
    fun `parses what the server pushes and ignores unknown fields`() {
        val c = Change.parse("""{"t":"files","f":["a",""],"trash":true,"x":1}""")!!
        assertThat(c.topic).isEqualTo(Change.FILES)
        assertThat(c.folders).containsExactly("a", "")
        assertThat(c.trash).isTrue()
        assertThat(Change.parse("oops")).isNull()
    }

    @Test
    fun `a folder change only touches the folders it lists, the root being the empty id`() {
        val c = Change(Change.FILES, folders = listOf("a", ""))
        assertThat(c.touchesFolder("a")).isTrue()
        assertThat(c.touchesFolder(null)).isTrue()
        assertThat(c.touchesFolder("b")).isFalse()
        assertThat(c.touchesTrash).isFalse()
        assertThat(c.touchesChat).isFalse()
        assertThat(Change(Change.FILES, all = true).touchesFolder("b")).isTrue()
    }

    @Test
    fun `chat, recent and resync`() {
        assertThat(Change(Change.CHAT).touchesChat).isTrue()
        assertThat(Change(Change.CHAT).touchesFolder(null)).isFalse()
        assertThat(Change(Change.RECENT).touchesRecent).isTrue()
        val resync = Change(Change.RESYNC)
        assertThat(resync.touchesFolder("any")).isTrue()
        assertThat(resync.touchesTrash).isTrue()
        assertThat(resync.touchesChat).isTrue()
    }
}
