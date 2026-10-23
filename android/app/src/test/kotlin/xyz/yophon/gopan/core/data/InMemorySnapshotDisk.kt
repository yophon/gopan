package xyz.yophon.gopan.core.data

import kotlinx.coroutines.CompletableDeferred

/** 测试用的 [SnapshotDisk]:同步写进内存;[readGate] 非空时 read 先等它(测"读盘途中内存被写") */
class InMemorySnapshotDisk : SnapshotDisk {
    val files = mutableMapOf<String, String>()
    var writes = 0
    var readGate: CompletableDeferred<Unit>? = null

    override suspend fun read(userId: String, key: String): String? {
        readGate?.await()
        return files["$userId/$key"]
    }

    override fun write(userId: String, key: String, encode: () -> String) {
        writes++
        files["$userId/$key"] = encode()
    }

    override fun remove(userId: String, key: String) {
        files.remove("$userId/$key")
    }

    override fun clear() = files.clear()
}
