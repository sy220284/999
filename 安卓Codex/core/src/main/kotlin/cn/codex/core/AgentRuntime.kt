package cn.codex.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 同一进程内执行模型 → 工具 → 审批 → 结果回注；不连接桌面 app-server。 */
class AgentRuntime(private val client: ResponsesClient, private val tools: LocalTools, private val approve: suspend (Approval) -> Boolean, private val steeringInbox: SteeringInbox? = null, private val journal: ToolAttemptJournal? = null) {
    suspend fun run(initial: Session, prompt: String, attachments: List<JsonObject> = emptyList(), emit: (AgentEvent) -> Unit): Session {
        var session = initial
        val input = initial.wire.toMutableList()
        // 上次执行中断时可能留下尚未配对的调用；绝不自动重放副作用。
        val returned = input.mapNotNull { (it as? JsonObject)?.takeIf { x -> x.string("type") == "function_call_output" }?.string("call_id") }.toSet()
        val orphaned = input.mapNotNull { (it as? JsonObject)?.takeIf { x -> x.string("type") == "function_call" && x.string("call_id") !in returned } }
        for (orphan in orphaned) {
            val callId = orphan.string("call_id")
            val old = journal?.read(session.id)?.firstOrNull { it.callId == callId }
            val output = if (old?.status == "completed" && old.output != null) old.output
                else "工具执行状态未知：上次进程在调用过程中中断。未自动重放；请先核查文件和命令的实际结果。"
            input += obj("type" to "function_call_output", "call_id" to callId, "output" to output)
            session = session.copy(
                wire = JsonArray(input),
                messages = session.messages + Message(role = "tool", text = orphan.string("name"), detail = output),
                updated = System.currentTimeMillis()
            )
            emit(AgentEvent.Updated(session))
            journal?.acknowledge(session.id, callId)
        }
        session = session.copy(
            title = if (session.messages.isEmpty()) prompt.take(30) else session.title,
            messages = session.messages + Message(role = "user", text = prompt),
            updated = System.currentTimeMillis()
        )
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
            // 先保存模型的函数调用和待执行日志，再运行任何有副作用的工具。
            if (calls.isNotEmpty()) {
                journal?.begin(session.id, calls.map { wireCall ->
                    ToolCall(wireCall.string("call_id"), wireCall.string("name"),
                        Json.parseToJsonElement(wireCall.string("arguments")).jsonObject)
                })
                session = session.copy(wire = JsonArray(input), updated = System.currentTimeMillis())
                emit(AgentEvent.Updated(session))
            }
            for (wireCall in calls) {
                currentCoroutineContext().ensureActive()
                val name = wireCall.string("name")
                emit(AgentEvent.Tool(name, wireCall.string("arguments"), true))
                journal?.started(session.id, wireCall.string("call_id"))
                val result = try {
                    val args = Json.parseToJsonElement(wireCall.string("arguments")).jsonObject
                    tools.execute(ToolCall(wireCall.string("call_id"), name, args), approve, emit)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "工具参数错误：${e.message}" }
                journal?.completed(session.id, wireCall.string("call_id"), result)
                input += obj("type" to "function_call_output", "call_id" to wireCall.string("call_id"), "output" to result)
                session = session.copy(wire = JsonArray(input), messages = session.messages + Message(role = "tool", text = name, detail = result), updated = System.currentTimeMillis())
                emit(AgentEvent.Updated(session))
                journal?.acknowledge(session.id, wireCall.string("call_id"))
                emit(AgentEvent.Tool(name, result, false))
            }
            session = session.copy(wire = JsonArray(input), updated = System.currentTimeMillis())
            emit(AgentEvent.Updated(session))
            // 按提交顺序注入追加指令，不在当前工具调用中间打断状态。
            // 先持久化会话中消耗的 ID，再从信箱确认，避免崩溃后重复注入。
            val queued = steeringInbox?.pending(session.id, session.consumedSteeringIds.toSet()).orEmpty()
            if (queued.isNotEmpty()) {
                queued.forEach { item ->
                    input += buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { add(obj("type" to "input_text", "text" to item.text)) })
                    }
                }
                session = session.copy(
                    wire = JsonArray(input),
                    messages = session.messages + queued.map { Message(role = "user", text = it.text) },
                    consumedSteeringIds = session.consumedSteeringIds + queued.map { it.id },
                    updated = System.currentTimeMillis()
                )
                emit(AgentEvent.Updated(session))
                steeringInbox?.acknowledge(session.id, queued.map { it.id }.toSet())
            }
            if (calls.isEmpty() && queued.isEmpty()) return session
        }
        error("本轮达到 100 次模型调用，请检查任务并继续")
    }
}
