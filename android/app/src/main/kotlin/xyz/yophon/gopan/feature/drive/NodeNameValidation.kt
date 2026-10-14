package xyz.yophon.gopan.feature.drive

/** 新建文件夹 / 重命名的本地校验结果;null 表示合法 */
enum class NameError { EMPTY, ILLEGAL_CHAR, RESERVED, TOO_LONG }

private const val MAX_NAME_BYTES = 255

/**
 * 与服务端 validateName、网页端完全对齐(非空、≤255 字节、不含 '/' 与 NUL、不是 . 或 ..)。
 * 不额外禁 '\':服务端与网页端都允许,本地多禁一个字符会让已有的这类节点在 App 里无法重命名。
 * 先 trim 再校验,调用方提交的也应是 trim 后的值。
 */
fun validateNodeName(raw: String): NameError? {
    val name = raw.trim()
    if (name.isEmpty()) return NameError.EMPTY
    if (name.any { it == '/' || it == '\u0000' }) return NameError.ILLEGAL_CHAR
    if (name == "." || name == "..") return NameError.RESERVED
    if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return NameError.TOO_LONG
    return null
}
