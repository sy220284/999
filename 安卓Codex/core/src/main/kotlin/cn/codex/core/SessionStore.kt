package cn.codex.core

import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 索引只读会话摘要，打开侧栏不会加载全部对话正文。 */
class SessionStore(private val directory: File) {
    private val index = File(directory, "会话索引.json")
    init { directory.mkdirs() }
    @Synchronized fun save(session: Session) {
        require(session.id.matches(Regex("[a-zA-Z0-9-]+")))
        atomic(File(directory, "${session.id}.json"), codec.encodeToString(session))
        val summaries = summaries().filterNot { it.id == session.id } + SessionSummary(session.id, session.title, session.project, session.archived, session.updated)
        atomic(index, codec.encodeToString(summaries))
    }
    @Synchronized fun list(includeArchived: Boolean = false) = summaries().filter { includeArchived || !it.archived }.sortedByDescending { it.updated }
    @Synchronized private fun summaries(): List<SessionSummary> {
        if (!index.exists()) return emptyList()
        return codec.decodeFromString(index.readText())
    }
    @Synchronized fun load(id: String): Session {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return codec.decodeFromString(File(directory, "$id.json").readText())
    }
    @Synchronized fun fork(id: String): Session = load(id).copy(id = java.util.UUID.randomUUID().toString(), title = load(id).title + " · 分支", archived = false, updated = System.currentTimeMillis()).also(::save)
    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val target = File(directory, "$id.json")
        require(target.isFile) { "会话不存在" }
        val original = summaries()
        atomic(index, codec.encodeToString(original.filterNot { it.id == id }))
        if (!target.delete()) {
            atomic(index, codec.encodeToString(original))
            error("删除会话失败")
        }
    }
    private fun atomic(file: File, text: String) {
        val temp = File.createTempFile("session-", ".tmp", directory)
        try {
            temp.outputStream().use { stream -> stream.write(text.toByteArray()); stream.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
