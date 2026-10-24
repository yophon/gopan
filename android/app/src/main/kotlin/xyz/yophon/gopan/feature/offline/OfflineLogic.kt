package xyz.yophon.gopan.feature.offline

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.offline.OfflineFileEntity

/** 离线文件夹项里的一个子目录:[path] 相对文件夹项,带上它底下(含更深层)的文件数与字节 */
@Immutable
data class OfflineSubfolder(val name: String, val path: String, val files: Int, val bytes: Long)

@Immutable
data class OfflineFolderListing(val folders: List<OfflineSubfolder>, val files: List<OfflineFileEntity>)

/**
 * 文件在文件夹项里的目录:登记的 relDir 以文件夹项自己的名字开头(「照片」「照片/2024」),去掉这一段;
 * 单个文件的离线项 relDir 是空串,也得到空串。
 */
fun offlineRelPath(relDir: String): String = relDir.substringAfter('/', "")

/** 文件夹项里 [path] 这一层:子目录(按名字)与这一层的文件(按名字) */
fun listOfflineFolder(files: List<OfflineFileEntity>, path: String): OfflineFolderListing {
    val prefix = if (path.isEmpty()) "" else "$path/"
    val here = mutableListOf<OfflineFileEntity>()
    val below = linkedMapOf<String, MutableList<OfflineFileEntity>>()
    for (f in files) {
        val rel = offlineRelPath(f.relDir)
        when {
            rel == path -> here += f
            rel.startsWith(prefix) && rel.length > prefix.length -> below.getOrPut(rel.removePrefix(prefix).substringBefore('/')) { mutableListOf() } += f
        }
    }
    val byName = String.CASE_INSENSITIVE_ORDER
    return OfflineFolderListing(
        folders = below.map { (name, list) -> OfflineSubfolder(name, prefix + name, list.size, list.sumOf { it.size }) }
            .sortedWith(compareBy(byName) { it.name }),
        files = here.sortedWith(compareBy(byName) { it.name }),
    )
}
