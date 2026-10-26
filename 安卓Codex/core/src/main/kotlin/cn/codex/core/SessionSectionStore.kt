package cn.codex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class SessionSection(
    val id: String = UUID.randomUUID().toString(),
    val project: String,
    val name: String,
    val created: Long = System.currentTimeMillis()
)

/** 项目级会话分类。分类删除前必须先移走会话，不会隐式删除任何聊天历史。 */
class SessionSectionStore(private val directory: File) {
    private val index = File(directory, "会话分类.json")
    init { directory.mkdirs() }
    @Synchronized fun list(project: String? = null): List<SessionSection> {
        val items: List<SessionSection> = if (index.exists()) codec.decodeFromString(index.readText()) else emptyList()
        return items.filter { project == null || it.project == project }.sortedWith(compareBy({ it.created }, { it.name }))
    }
    @Synchronized fun create(project: String, name: String): SessionSection {
        val label = name.trim()
        require(project.isNotBlank() && label.length in 1..50) { "分类名称应为 1–50 个字符" }
        require(list(project).none { it.name == label }) { "同名分类已存在" }
        val created = SessionSection(project = project, name = label)
        save(list() + created)
        return created
    }
    @Synchronized fun rename(id: String, name: String) {
        val existing = list().firstOrNull { it.id == id } ?: error("分类不存在")
        val label = name.trim()
        require(label.length in 1..50) { "分类名称应为 1–50 个字符" }
        require(list(existing.project).none { it.id != id && it.name == label }) { "同名分类已存在" }
        save(list().map { if (it.id == id) it.copy(name = label) else it })
    }
    @Synchronized fun remove(id: String, summaries: List<SessionSummary>) {
        require(list().any { it.id == id }) { "分类不存在" }
        require(summaries.none { it.sectionId == id }) { "请先将该分类里的会话移走" }
        save(list().filterNot { it.id == id })
    }
    private fun save(sections: List<SessionSection>) {
        val temp = File.createTempFile("sections-", ".tmp", directory)
        try {
            temp.outputStream().use { out ->
                out.write(codec.encodeToString(sections).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
