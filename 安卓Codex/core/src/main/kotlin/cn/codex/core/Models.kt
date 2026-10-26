package cn.codex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: default
fun JsonObject.int(key: String, default: Int) = this[key]?.jsonPrimitive?.intOrNull ?: default
fun obj(vararg entries: Pair<String, String>) = buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }

@Serializable
data class ApiConfig(
    val endpoint: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "",
    val reasoning: String = "medium",
    val allowLoopbackHttp: Boolean = false,
    val theme: String = "system",
    val motion: Boolean = true
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val detail: String = "",
    val status: String = "done",
    val created: Long = System.currentTimeMillis()
)

@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val project: String,
    val messages: List<Message> = emptyList(),
    val wire: JsonArray = JsonArray(emptyList()),
    val archived: Boolean = false,
    val goal: String = "",
    val consumedSteeringIds: List<String> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
    val sectionId: String? = null
)

@Serializable
data class SessionSummary(val id: String, val title: String, val project: String, val archived: Boolean, val updated: Long, val sectionId: String? = null)
@Serializable
data class FileChange(val path: String, val before: String?, val after: String?)
data class Approval(val title: String, val detail: String, val changes: List<FileChange> = emptyList(), val shell: Boolean = false)
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    fun wire() = buildJsonObject { put("type", "function"); put("name", name); put("description", description); put("parameters", parameters); put("strict", false) }
}
sealed interface AgentEvent {
    data class Delta(val text: String, val reasoning: Boolean = false): AgentEvent
    data class Updated(val session: Session): AgentEvent
    data class Tool(val name: String, val detail: String, val running: Boolean): AgentEvent
    data class Usage(val input: Int, val output: Int): AgentEvent
}
