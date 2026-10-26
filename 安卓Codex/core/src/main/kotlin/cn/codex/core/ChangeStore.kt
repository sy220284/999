package cn.codex.core

import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 按项目隔离撤销记录，切换项目不能把同名文件的历史混在一起。 */
class ChangeStore(private val directory: File) {
    init { directory.mkdirs() }
    private val file = File(directory, "修改记录.json")
    @Synchronized private fun all(): Map<String, List<FileChange>> = if (file.exists()) codec.decodeFromString(file.readText()) else emptyMap()
    @Synchronized fun list(project: String) = all()[project].orEmpty()
    @Synchronized fun append(project: String, changes: List<FileChange>) = save(all().toMutableMap().apply { put(project, get(project).orEmpty() + changes) })
    @Synchronized fun remove(project: String, change: FileChange) {
        val values = list(project).toMutableList()
        val index = values.lastIndexOf(change)
        check(index >= 0) { "修改记录不存在" }
        values.removeAt(index)
        save(all().toMutableMap().apply { put(project, values) })
    }
    private fun save(data: Map<String, List<FileChange>>) {
        val temp = File.createTempFile("changes-", ".tmp", directory)
        try { temp.writeText(codec.encodeToString(data)); Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) } finally { temp.delete() }
    }
}
