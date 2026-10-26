package cn.codex.core

/** 对照上游 apply-patch 的 Begin/End/Add/Update/Delete 格式，先完整预演再写入。 */
internal class PatchParser(private val fs: Workspace) {
    fun parse(patch: String): List<FileChange> {
        val lines = patch.trimEnd().lines()
        require(lines.firstOrNull() == "*** Begin Patch" && lines.lastOrNull() == "*** End Patch") { "补丁必须以 Begin Patch 开始、End Patch 结束" }
        val changes = mutableListOf<FileChange>()
        var i = 1
        while (i < lines.lastIndex) {
            val header = lines[i++]
            val operation = when { header.startsWith("*** Add File: ") -> "add"; header.startsWith("*** Update File: ") -> "update"; header.startsWith("*** Delete File: ") -> "delete"; else -> error("未知补丁头：$header") }
            val path = header.substringAfter(": ")
            val old = fs.resolve(path).takeIf { it.isFile }?.let { fs.read(path) }
            when (operation) {
                "add" -> {
                    require(old == null && !fs.resolve(path).exists()) { "新文件已存在：$path" }
                    val added = mutableListOf<String>()
                    while (i < lines.lastIndex && !lines[i].startsWith("*** ")) { require(lines[i].startsWith("+")) { "新增行必须以 + 开头" }; added += lines[i++].drop(1) }
                    changes += FileChange(path, null, added.joinToString("\n", postfix = if (added.isEmpty()) "" else "\n"))
                }
                "delete" -> { require(old != null) { "文件不存在：$path" }; changes += FileChange(path, old, null) }
                else -> {
                    require(old != null) { "文件不存在：$path" }
                    val current = old.removeSuffix("\n").split("\n").toMutableList()
                    var searchFrom = 0
                    var trailingNewline = old.endsWith("\n")
                    while (i < lines.lastIndex && !lines[i].startsWith("*** Update File:") && !lines[i].startsWith("*** Add File:") && !lines[i].startsWith("*** Delete File:")) {
                        require(lines[i].startsWith("@@")) { "修改块需以 @@ 开始；不支持 Move to，请使用写入和删除" }
                        i++
                        val before = mutableListOf<String>(); val after = mutableListOf<String>()
                        while (i < lines.lastIndex && !lines[i].startsWith("@@") && !lines[i].startsWith("*** ")) {
                            val line = lines[i++]
                            when (line.firstOrNull()) {
                                ' ' -> { before += line.drop(1); after += line.drop(1) }
                                '-' -> before += line.drop(1)
                                '+' -> after += line.drop(1)
                                else -> error("修改行需带空格、+ 或 - 前缀")
                            }
                        }
                        if (i < lines.lastIndex && lines[i] == "*** End of File") { trailingNewline = false; i++ }
                        val at = if (before.isEmpty()) current.size else (searchFrom..(current.size - before.size)).firstOrNull { p -> current.subList(p, p + before.size) == before } ?: error("补丁上下文不匹配：$path")
                        repeat(before.size) { current.removeAt(at) }; current.addAll(at, after); searchFrom = at + after.size
                    }
                    changes += FileChange(path, old, current.joinToString("\n") + if (trailingNewline) "\n" else "")
                }
            }
        }
        require(changes.isNotEmpty()) { "补丁为空" }
        require(changes.map { it.path }.distinct().size == changes.size) { "一个补丁不能重复修改同一路径" }
        return changes
    }
}
