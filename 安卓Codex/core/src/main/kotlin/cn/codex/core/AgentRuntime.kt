package cn.codex.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 同一进程内执行模型 → 工具 → 审批 → 结果回注；不连接桌面 app-server。 */
class AgentRuntime(private val client: ResponsesClient, private val tools: LocalTools, private val approve: suspend (Approval) -> Boolean) {
    suspend fun run(initial: Session, prompt: String, attachments: List<JsonObject> = emptyList(), emit: (AgentEvent) -> Unit): Session {
        var session = initial.copy(
            title = if (initial.messages.isEmpty()) prompt.take(30) else initial.title,
            messages = initial.messages + Message(role = "user", text = prompt),
            updated = System.currentTimeMillis()
        )
        val input = initial.wire.toMutableList()
        input += buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray { add(obj("type" to "input_text", "text" to prompt)); attachments.forEach(::add) })
        }
        session = session.copy(wire = JsonArray(input)); emit(AgentEvent.Updated(session))
        val instructions = buildString {
            append("你是 Android 手机上的 Codex 编程助手。使用中文回复，围绕用户目标工作。所有工具在本机执行。")
            append("项目路径：${tools.fs.root.absolutePath}。文件工具限制在项目内，shell 仅受应用 UID 约束，不是桌面沙箱。")
            append("修改与命令执行需要用户批准。被拒绝时尊重决定。不能假装命令成功，不能假设有桌面开发工具。")
            append("先读取相关文件，再修改，再验证实际结果。未经请求不派生子代理、不设置定时任务。")
            if (session.goal.isNotBlank()) append("\n当前目标：${session.goal}")
            for (file in listOf("AGENTS.md", "说明.md")) {
                if (tools.fs.resolve(file).isFile) append("\n项目指令 $file：\n${tools.fs.read(file).take(24000)}")
            }
        }
        repeat(100) {
            currentCoroutineContext().ensureActive()
            val output = client.respond(JsonArray(input), tools.specs(), instructions, emit)
            input.addAll(output)
            val text = output.map { it.jsonObject }.filter { it.string("type") == "message" }.flatMap { it["content"]?.jsonArray ?: JsonArray(emptyList()) }.map { it.jsonObject }.mapNotNull { part -> when (part.string("type")) {
                "output_text" -> part.string("text")
                "refusal" -> part.string("refusal")
                else -> null
            } }.filter { it.isNotBlank() }.joinToString("\n")
            if (text.isNotBlank()) session = session.copy(messages = session.messages + Message(role = "assistant", text = text))
            val calls = output.map { it.jsonObject }.filter { it.string("type") == "function_call" }
            // 不在所有工具结果回注前保存悬空的 function_call；重启后绝不自动重放副作用。
            for (wireCall in calls) {
                currentCoroutineContext().ensureActive()
                val name = wireCall.string("name")
                emit(AgentEvent.Tool(name, wireCall.string("arguments"), true))
                val result = try {
                    val args = Json.parseToJsonElement(wireCall.string("arguments")).jsonObject
                    tools.execute(ToolCall(wireCall.string("call_id"), name, args), approve, emit)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "工具参数错误：${e.message}" }
                input += obj("type" to "function_call_output", "call_id" to wireCall.string("call_id"), "output" to result)
                session = session.copy(messages = session.messages + Message(role = "tool", text = name, detail = result))
                emit(AgentEvent.Tool(name, result, false))
            }
            session = session.copy(wire = JsonArray(input), updated = System.currentTimeMillis())
            emit(AgentEvent.Updated(session))
            if (calls.isEmpty()) return session
        }
        error("本轮达到 100 次模型调用，请检查任务并继续")
    }
}
