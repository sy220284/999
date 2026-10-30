package cn.codex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class SteeringInstruction(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val created: Long = System.currentTimeMillis()
)

/**
 * 本机待处理指令信箱。只有在 Session.wire 和 consumedSteeringIds 保存成功后才能确认消费。
 * 进程意外退出后，已消费 ID 可以阻止重复注入；未消费的指令仍留在磁盘。
 */
class SteeringInbox(private val directory: File) {
    init { directory.mkdirs() }
    private fun path(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9-]+"))) { "会话 ID 无效" }
        return File(directory, "$sessionId.json")
    }
    @Synchronized private fun read(sessionId: String): List<SteeringInstruction> {
        val file = path(sessionId)
        return if (file.exists()) codec.decodeFromString(file.readText()) else emptyList()
    }
    @Synchronized fun add(sessionId: String, text: String): SteeringInstruction {
        require(text.isNotBlank() && text.length <= 100_000) { "追加指令不能为空或过长" }
        val item = SteeringInstruction(text = text)
        write(sessionId, read(sessionId) + item)
        return item
    }
    @Synchronized fun pending(sessionId: String, consumed: Set<String>): List<SteeringInstruction> =
        read(sessionId).filterNot { it.id in consumed }
    @Synchronized fun acknowledge(sessionId: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        write(sessionId, read(sessionId).filterNot { it.id in ids })
    }
    @Synchronized fun clear(sessionId: String) {
        Files.deleteIfExists(path(sessionId).toPath())
    }
    private fun write(sessionId: String, items: List<SteeringInstruction>) {
        val target = path(sessionId)
        val temp = File.createTempFile("steer-", ".tmp", directory)
        try {
            temp.outputStream().use { out ->
                out.write(codec.encodeToString(items).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
