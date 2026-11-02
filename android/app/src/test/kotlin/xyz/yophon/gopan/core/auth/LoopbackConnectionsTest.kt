package xyz.yophon.gopan.core.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoopbackConnectionsTest {
    @Test
    fun `debug build allows cleartext only for loopback issuers`() {
        assertThat(LoopbackConnections.allowsCleartext("http://localhost:3040", debug = true)).isTrue()
        assertThat(LoopbackConnections.allowsCleartext("http://127.0.0.1:3040", debug = true)).isTrue()
        assertThat(LoopbackConnections.allowsCleartext("http://10.0.2.2:3040", debug = true)).isTrue()
        assertThat(LoopbackConnections.allowsCleartext("http://id.example.com", debug = true)).isFalse()
        assertThat(LoopbackConnections.allowsCleartext("https://id.example.com", debug = true)).isFalse()
        assertThat(LoopbackConnections.allowsCleartext("not a uri", debug = true)).isFalse()
    }

    @Test
    fun `release build never allows cleartext`() {
        assertThat(LoopbackConnections.allowsCleartext("http://localhost:3040", debug = false)).isFalse()
        assertThat(LoopbackConnections.allowsCleartext("https://id.example.com", debug = false)).isFalse()
    }
}
