package cn.codex.core.original

import kotlinx.serialization.json.*

data class OriginalApprovalPreview(val title: String, val body: String, val reviewable: Boolean)

/** v1 审批使用 ReviewDecision；不能使用 v2 的 accept/decline。只允许完整可审阅的声明。 */
object OriginalLegacyApproval {
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun preview(method: String, params: JsonObject): OriginalApprovalPreview {
        val title = if (method == "applyPatchApproval") "确认旧版文件修改" else "确认旧版命令执行"
        return try {
            require(params.toString().length <= 100_000) { "操作内容过长，无法完整预览，请拒绝后缩小范围" }
            require(!params.text("conversationId").isNullOrBlank() && !params.text("callId").isNullOrBlank()) { "缺少会话或操作标识" }
            for (key in listOf("reason", "grantRoot")) {
                require(params[key] == null || params[key] == JsonNull || params.text(key) != null) { "$key 字段格式不完整" }
            }
            val body = buildString {
                appendLine("会话：${params.text("conversationId")}")
                params.text("reason")?.let { appendLine("原因：$it") }
                when (method) {
                    "execCommandApproval" -> {
                        val command = params["command"] as? JsonArray ?: error("缺少命令参数")
                        require(command.isNotEmpty() && command.all { it is JsonPrimitive && it.isString }) { "命令参数格式不完整" }
                        require(command.first().jsonPrimitive.content.isNotBlank()) { "缺少可执行命令" }
                        val cwd = params.text("cwd"); require(!cwd.isNullOrBlank()) { "缺少工作目录" }
                        appendLine("目录：$cwd")
                        appendLine("逐项命令参数（按原顺序，未重新拼接为 shell 命令）：")
                        command.forEachIndexed { index, arg -> appendLine("[$index] $arg") }
                        append("允许后以本应用身份执行此操作。")
                    }
                    "applyPatchApproval" -> {
                        val changes = params["fileChanges"] as? JsonObject ?: error("缺少文件变更")
                        require(changes.isNotEmpty()) { "没有可审阅的文件变更" }
                        params.text("grantRoot")?.let { root ->
                            require(root.isNotBlank()) { "写入授权目录为空" }
                            appendLine("此请求还涉及会话期间的写入授权目录：$root。批准可能影响本会话后续写入。")
                        }
                        changes.forEach { (path, element) ->
                            require(path.isNotBlank()) { "缺少文件路径" }
                            val change = element as? JsonObject ?: error("文件变更格式不完整")
                            when (change.text("type")) {
                                "add", "delete" -> {
                                    val content = change.text("content") ?: error("缺少完整文件内容")
                                    appendLine("${if (change.text("type") == "add") "新增" else "删除"}：$path")
                                    appendLine(content)
                                }
                                "update" -> {
                                    val diff = change.text("unified_diff") ?: error("缺少修改差异")
                                    appendLine("修改：$path")
                                    if (change["move_path"] != null && change["move_path"] != JsonNull) {
                                        val target = change.text("move_path"); require(!target.isNullOrBlank()) { "移动目标无效" }
                                        appendLine("移动到：$target")
                                    }
                                    appendLine(diff)
                                }
                                else -> error("无法审阅此文件变更类型")
                            }
                        }
                    }
                    else -> error("未知旧版审批方法")
                }
            }
            require(body.length <= 100_000) { "操作内容过长，无法完整预览" }
            OriginalApprovalPreview(title, body, true)
        } catch (failure: IllegalArgumentException) {
            OriginalApprovalPreview(title, failure.message ?: "操作声明不完整，请拒绝", false)
        } catch (failure: IllegalStateException) {
            OriginalApprovalPreview(title, failure.message ?: "操作声明不完整，请拒绝", false)
        }
    }
    fun decision(allow: Boolean): JsonObject = buildJsonObject {
        if (allow) put("decision", "approved")
        else put("decision", buildJsonObject { put("denied", buildJsonObject { put("rejection", "用户拒绝此次操作") }) })
    }
}
