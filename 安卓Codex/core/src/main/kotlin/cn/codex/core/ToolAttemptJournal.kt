package cn.codex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class ToolAttempt(
    val callId: String,
    val name: String,
    val arguments: String,
    val status: String = "pending",
    val output: String? = null
)

/**
 * 工具调用落盘日志。先记下待执行调用，再标记 started，执行后记 completed。
 * 只有会话的 function_call_output 已持久化，才删除日志。
 * started 发生崩溃时绝不盲目重放；completed 允许恢复实际结果。
 */
class ToolAttemptJournal(private val directory: File) {
    init { directory.mkdirs() }
    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "会话 ID 无效" }
        return File(directory, "$id.json")
    }
    @Synchronized fun read(sessionId: String): List<ToolAttempt> {
        val path = file(sessionId)
        return if (path.exists()) codec.decodeFromString(path.readText()) else emptyList()
    }
    @Synchronized fun begin(sessionId: String, calls: List<ToolCall>) {
        val state = read(sessionId).toMutableList()
        calls.forEach { call ->
            require(call.id.isNotBlank()) { "工具调用缺少 ID" }
            val existing = state.firstOrNull { it.callId == call.id }
            if (existing == null) state += ToolAttempt(call.id, call.name, call.arguments.toString())
            else require(existing.name == call.name && existing.arguments == call.arguments.toString()) { "工具调用 ID 冲突" }
        }
        write(sessionId, state)
    }
    @Synchronized fun started(sessionId: String, callId: String) = update(sessionId, callId) { it.copy(status = "started") }
    @Synchronized fun completed(sessionId: String, callId: String, output: String) = update(sessionId, callId) { it.copy(status = "completed", output = output) }
    @Synchronized fun acknowledge(sessionId: String, callId: String) {
        write(sessionId, read(sessionId).filterNot { it.callId == callId })
    }
    @Synchronized fun clear(sessionId: String) { Files.deleteIfExists(file(sessionId).toPath()) }
    private fun update(sessionId: String, callId: String, transform: (ToolAttempt) -> ToolAttempt) {
        val entries = read(sessionId)
        require(entries.any { it.callId == callId }) { "工具调用不存在" }
        write(sessionId, entries.map { if (it.callId == callId) transform(it) else it })
    }
    private fun write(sessionId: String, items: List<ToolAttempt>) {
        val path = file(sessionId)
        val temp = File.createTempFile("attempt-", ".tmp", directory)
        try {
            temp.outputStream().use { out ->
                out.write(codec.encodeToString(items).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
