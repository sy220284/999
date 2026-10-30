package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 补充运行信息与完整 item 正文分离，避免 item/completed 覆盖进度或把进度拼进模型回答。 */
data class OriginalTurnProgress(
    val plan: JsonObject? = null,
    val usage: JsonObject? = null,
    val tools: Map<String, String> = emptyMap()
)
object OriginalLiveUpdates {
    fun reduce(previous: Map<String, OriginalTurnProgress>, method: String, params: JsonObject): Map<String, OriginalTurnProgress> {
        val turn = params.string("turnId")
        if (turn.isBlank()) return previous
        var next = previous[turn] ?: OriginalTurnProgress()
        when (method) {
            "turn/plan/updated" -> {
                if (params["plan"] !is JsonArray) return previous
                // 计划为补充展示；限制缓存，超长内容明确提示，并保留整段步骤而非截断 JSON。
                val plan = params["plan"] as JsonArray
                var budget = 65_536
                val retained = plan.takeWhile { step -> budget -= step.toString().length; budget >= 0 }
                next = next.copy(plan = buildJsonObject {
                    put("plan", JsonArray(retained))
                    put("explanation", params.string("explanation").take(4096))
                    put("truncated", retained.size != plan.size || params.string("explanation").length > 4096)
                })
            }
            "thread/tokenUsage/updated" -> {
                val usage = params["tokenUsage"] as? JsonObject ?: return previous
                next = next.copy(usage = usage)
            }
            "item/mcpToolCall/progress", "item/commandExecution/terminalInteraction", "item/fileChange/outputDelta" -> {
                val item = params.string("itemId"); if (item.isBlank()) return previous
                val text = when (method) {
                    "item/mcpToolCall/progress" -> "工具进度：${params.string("message")}\n"
                    "item/commandExecution/terminalInteraction" -> "终端输入记录 · 进程 ${params.string("processId")}：\n${params.string("stdin")}\n"
                    else -> params.string("delta")
                }
                val tools = LinkedHashMap(next.tools)
                val full = tools[item].orEmpty() + text
                tools[item] = if (full.length > 32_768) "（仅保留最近的进度片段）\n" + full.takeLast(32_700) else full
                next = next.copy(tools = tools.entries.toList().takeLast(32).associate { it.toPair() })
            }
            else -> return previous
        }
        // 同一回合保持原顺序；迟到旧回合信息不会覆盖新回合数据。
        return LinkedHashMap(previous).apply { put(turn, next) }.entries.toList().takeLast(16).associate { it.toPair() }
    }
    fun describe(progress: OriginalTurnProgress): String = buildString {
        progress.plan?.let { plan ->
            appendLine("任务计划")
            if ((plan["truncated"] as? JsonPrimitive)?.booleanOrNull == true) appendLine("计划过长，仅展示部分内容")
            plan.string("explanation").takeIf { it.isNotBlank() }?.let { appendLine(it) }
            (plan["plan"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { step ->
                val status = when (val raw = step.string("status")) { "pending" -> "待处理"; "inProgress" -> "进行中"; "completed" -> "已完成"; else -> raw.ifBlank { "状态未提供" } }
                appendLine("$status · ${step.string("step")}")
            }
        }
        progress.usage?.let { usage ->
            fun number(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }?.toString() ?: "未提供"
            appendLine("Token 用量（核心报告）")
            listOf("last" to "最近一次", "total" to "会话累计").forEach { (key, label) ->
                val row = usage[key] as? JsonObject
                appendLine("$label：合计 ${number(row, "totalTokens")} · 输入 ${number(row, "inputTokens")} · 输出 ${number(row, "outputTokens")}")
                appendLine("缓存读取 ${number(row, "cachedInputTokens")} · 缓存写入 ${number(row, "cacheWriteInputTokens")} · 推理输出 ${number(row, "reasoningOutputTokens")}")
            }
            appendLine("模型上下文窗口：${number(usage, "modelContextWindow")}")
        }
        progress.tools.forEach { (id, text) -> appendLine("操作 $id\n$text") }
    }.trimEnd()
}
