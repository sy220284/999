package cn.codex.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

interface ToolExtension {
    fun specs(): List<ToolSpec>
    suspend fun execute(call: ToolCall): String
}

class LocalTools(
    val fs: Workspace,
    val shell: ShellRunner,
    private val extensions: List<ToolExtension> = emptyList(),
    private val changed: (List<FileChange>) -> Unit = {}
) {
    private fun spec(name: String, description: String, vararg fields: Pair<String, String>, required: List<String> = fields.map { it.first }) = ToolSpec(name, description, buildJsonObject {
        put("type", "object"); put("properties", buildJsonObject { fields.forEach { (key, type) -> put(key, obj("type" to type)) } }); put("required", JsonArray(required.map(::JsonPrimitive)))
    })
    fun specs(): List<ToolSpec> = listOf(
        spec("list_files", "列出当前项目目录的文件名与类型，不读取文件内容", "path" to "string", required = emptyList()),
        spec("read_file", "按行读取 UTF-8 文本，默认从第一行读取 300 行", "path" to "string", "start_line" to "integer", "limit" to "integer", required = listOf("path")),
        spec("search_files", "搜索项目文件名和文本内容，最多返回 200 处", "query" to "string", "content" to "boolean", required = listOf("query")),
        spec("write_file", "写入 UTF-8 文件。向用户展示差异并获得批准后才生效", "path" to "string", "content" to "string"),
        spec("apply_patch", "修改项目文件。使用 *** Begin Patch / *** Add File: / *** Update File: / *** Delete File: / @@ / +/-/空格 / *** End Patch 格式；不支持 Move to", "patch" to "string"),
        spec("delete_file", "获得用户批准后删除一个文本文件", "path" to "string"),
        spec("stat_path", "获取项目文件或目录的存在状态、类型、大小和修改时间", "path" to "string"),
        spec("copy_path", "在项目内复制文件或整个目录，支持二进制内容；需要用户批准，目标不能存在", "source" to "string", "destination" to "string"),
        spec("remove_path", "删除项目内文件或目录，非空目录必须显式 recursive=true；需要用户批准", "path" to "string", "recursive" to "boolean", required = listOf("path")),
        spec("shell", "在手机应用权限内执行 /system/bin/sh 命令。没有 Linux 桌面沙箱，不能假设安装了 git/python/node。执行前需用户批准", "command" to "string", "timeout_seconds" to "integer", required = listOf("command")),
        spec("update_plan", "更新工作计划，使用简洁中文说明步骤、当前状态", "plan" to "string"),
        spec("list_skills", "列出项目内 技能/ 或 .agents/skills/ 中的 SKILL.md", required = emptyList()),
        spec("read_skill", "读取技能指令，path 为 list_skills 返回的相对路径", "path" to "string"),
        ToolSpec("set_skill_roots", "设置项目的额外技能搜索目录，路径必须位于项目内，修改需用户批准", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("roots", buildJsonObject {
                    put("type", "array")
                    put("items", obj("type" to "string"))
                })
            })
            put("required", buildJsonArray { add("roots") })
        })
    ) + extensions.flatMap { it.specs() }

    suspend fun execute(call: ToolCall, approve: suspend (Approval) -> Boolean, emit: (AgentEvent) -> Unit): String {
        val a = call.arguments
        val path = a.string("path")
        return try {
            when (call.name) {
                "list_files" -> fs.list(path).joinToString("\n") { (if (it.directory) "目录 " else "文件 ") + it.path + " (${it.size} B)" }
                "read_file" -> fs.readLines(path, a.int("start_line", 1), a.int("limit", 300))
                "search_files" -> fs.search(a.string("query"), a["content"]?.jsonPrimitive?.booleanOrNull ?: false)
                "write_file" -> apply(listOf(fs.previewWrite(path, a.string("content"))), approve)
                "apply_patch" -> apply(fs.previewPatch(a.string("patch")), approve)
                "delete_file" -> { require(fs.resolve(path).isFile) { "文件不存在" }; apply(listOf(fs.previewWrite(path, null)), approve) }
                "stat_path" -> fs.metadata(path).let { "路径：${it.path}\n类型：${if (it.directory) "目录" else "文件"}\n大小：${it.size} 字节\n修改时间戳：${it.modified}" }
                "copy_path" -> {
                    val source = a.string("source")
                    val target = a.string("destination")
                    // 复制前校验来源和目标，审批后由 Workspace 再次校验并执行原子复制。
                    fs.metadata(source)
                    require(!fs.resolve(target).exists()) { "目标已存在" }
                    if (!approve(Approval("确认复制项目内容", "$source → $target"))) "用户拒绝复制"
                    else { fs.copyPath(source, target); "已复制：$source → $target" }
                }
                "remove_path" -> {
                    val target = fs.metadata(path)
                    val recursive = a["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (target.directory && !recursive && !fs.resolve(path).listFiles().isNullOrEmpty()) error("非空目录需显式 recursive=true")
                    if (!approve(Approval("确认删除项目内容", "删除：$path\n类型：${if (target.directory) "目录" else "文件"}\n递归删除：$recursive"))) "用户拒绝删除"
                    else { fs.removePath(path, recursive); "已删除：$path" }
                }
                "shell" -> {
                    val command = a.string("command")
                    if (!approve(Approval("执行手机命令", command, shell = true))) "用户拒绝执行命令"
                    else {
                        val r = shell.execute(command, a.int("timeout_seconds", 120)) { emit(AgentEvent.Tool("shell", it, true)) }
                        "退出码：${r.exitCode}\n${r.output}" + if (r.truncated) "\n[输出已截断到 256KB]" else ""
                    }
                }
                "update_plan" -> a.string("plan").also { emit(AgentEvent.Tool("update_plan", it, false)) }
                "list_skills" -> SkillCatalog(fs).list().joinToString("\n")
                "read_skill" -> {
                    require(path in SkillCatalog(fs).list()) { "技能路径不在当前项目注册列表中" }
                    fs.read(path)
                }
                "set_skill_roots" -> {
                    val names = a["roots"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: error("需要 roots 字符串数组")
                    apply(listOf(SkillCatalog(fs).previewConfig(names)), approve)
                }
                else -> {
                    val ext = extensions.firstOrNull { x -> x.specs().any { it.name == call.name } } ?: error("未知工具：${call.name}")
                    if (!approve(Approval("使用扩展工具", "${call.name}\n${call.arguments}"))) "用户拒绝使用工具" else ext.execute(call)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "工具执行失败：${e.message ?: e.javaClass.simpleName}" }
    }
    private suspend fun apply(changes: List<FileChange>, approve: suspend (Approval) -> Boolean): String {
        if (!approve(Approval("确认文件修改", changes.joinToString("\n") { it.path }, changes))) return "用户拒绝文件修改，文件未变化"
        fs.commitAll(changes)
        changed(changes)
        return "已修改：" + changes.joinToString { it.path }
    }
}
