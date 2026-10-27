package cn.codex.android

import android.content.Context
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

// 前台服务持有任务；旋转、切换页面不会重建或重放正在执行的工作。
data class ScreenState(
    val config: ApiConfig = ApiConfig(), val session: Session? = null,
    val sessions: List<SessionSummary> = emptyList(), val sections: List<SessionSection> = emptyList(), val projects: List<String> = emptyList(),
    val project: String = "默认项目", val busy: Boolean = false, val canSteer: Boolean = false, val stream: String = "",
    val thinking: String = "", val tool: String = "", val terminal: String = "",
    val interactiveTerminal: Boolean = false,
    val approval: Approval? = null, val error: String? = null, val usage: String = "",
    val changes: List<FileChange> = emptyList(), val attachments: List<String> = emptyList()
)
class RuntimeController(private val context: Context) {
    private val settings = SecureSettings(context)
    private val home = File(context.filesDir, "工作区").apply { mkdirs() }
    val store = SessionStore(File(context.filesDir, "会话"))
    private val sectionStore = SessionSectionStore(File(context.filesDir, "会话"))
    private val changeStore = ChangeStore(File(context.filesDir, "修改历史"))
    private val steeringInbox = SteeringInbox(File(context.filesDir, "追加指令"))
    private val toolJournal = ToolAttemptJournal(File(context.filesDir, "工具执行日志"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> fail("数据操作失败：${error.message}") })
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var shell: ShellRunner? = null
    private var interactive: NativePty.Session? = null
    private val attachmentInputs = mutableListOf<JsonObject>()
    init {
        scope.launch {
            val config = withContext(Dispatchers.IO) { runSuspendCatching { settings.load() } }
            config.onSuccess { mutable.update { s -> s.copy(config = it) } }.onFailure { fail("无法读取已保存的连接设置，请重新填写：${it.message}") }
            refresh()
            newSession("默认项目")
            withContext(Dispatchers.IO) { changeStore.list(state.value.project) }.let { changes -> mutable.update { it.copy(changes = changes) } }
        }
    }
    fun workspace(project: String = state.value.project): Workspace {
        require(project.isNotBlank() && project !in setOf(".", "..") && '/' !in project && '\\' !in project) { "项目名不能包含路径分隔符" }
        return Workspace(File(home, project))
    }
    suspend fun refresh() {
        val data = withContext(Dispatchers.IO) {
            Triple(home.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty(),
                store.list(includeArchived = true), sectionStore.list())
        }
        mutable.update { it.copy(projects = data.first, sessions = data.second, sections = data.third) }
    }
    fun newSession(project: String = state.value.project) {
        if (state.value.busy) return
        workspace(project)
        if (project != state.value.project) closeInteractiveTerminal()
        mutable.update { it.copy(project = project, session = Session(project = project), stream = "", thinking = "", error = null, attachments = emptyList(), changes = emptyList()) }
        attachmentInputs.clear()
        scope.launch { val p = state.value.project; val changes = withContext(Dispatchers.IO) { changeStore.list(p) }; mutable.update { it.copy(changes = changes) }; refresh() }
    }
    fun openSession(id: String) { if (!state.value.busy) scope.launch { runSuspendCatching { withContext(Dispatchers.IO) { store.load(id) } }.onSuccess { s -> if (s.project != state.value.project) closeInteractiveTerminal(); mutable.update { it.copy(session = s, project = s.project, stream = "", thinking = "", error = null, attachments = emptyList(), changes = emptyList()) }; attachmentInputs.clear(); mutable.update { it.copy(changes = changeStore.list(s.project)) } }.onFailure { fail(it.message ?: "会话读取失败") } } }
    fun archive(id: String) = scope.launch { if (!state.value.busy) runSuspendCatching { withContext(Dispatchers.IO) { val s = store.load(id); store.save(s.copy(archived = !s.archived)) }; refresh() }.onFailure { fail(it.message.orEmpty()) } }
    fun deleteSession(id: String) = scope.launch {
        if (state.value.busy) return@launch
        runSuspendCatching {
            withContext(Dispatchers.IO) { store.delete(id); steeringInbox.clear(id); toolJournal.clear(id) }
            if (state.value.session?.id == id) newSession(state.value.project)
            refresh()
        }.onFailure { fail("删除会话失败：${it.message}") }
    }
    fun createSection(name: String) = scope.launch {
        if (state.value.busy) return@launch
        val project = state.value.project
        runSuspendCatching { withContext(Dispatchers.IO) { sectionStore.create(project, name) } }
            .onSuccess { refresh() }.onFailure { fail("创建分类失败：${it.message}") }
    }
    fun renameSection(id: String, name: String) = scope.launch {
        if (state.value.busy) return@launch
        runSuspendCatching { withContext(Dispatchers.IO) { sectionStore.rename(id, name) } }
            .onSuccess { refresh() }.onFailure { fail("修改分类失败：${it.message}") }
    }
    fun deleteSection(id: String) = scope.launch {
        if (state.value.busy) return@launch
        runSuspendCatching { withContext(Dispatchers.IO) { sectionStore.remove(id, store.list(includeArchived = true)) } }
            .onSuccess { refresh() }.onFailure { fail("删除分类失败：${it.message}") }
    }
    fun moveSession(id: String, sectionId: String?) = scope.launch {
        if (state.value.busy) return@launch
        runSuspendCatching {
            val changed = withContext(Dispatchers.IO) {
                val current = store.load(id)
                if (sectionId != null) require(sectionStore.list(current.project).any { it.id == sectionId }) { "目标分类不属于当前项目" }
                current.copy(sectionId = sectionId, updated = System.currentTimeMillis()).also { store.save(it) }
            }
            mutable.update { old -> if (old.session?.id == id) old.copy(session = changed) else old }
            refresh()
        }.onFailure { fail("移动会话失败：${it.message}") }
    }
    fun revertLastTask() = scope.launch {
        if (state.value.busy) return@launch
        val selected = state.value.session ?: return@launch
        val turnId = selected.lastTurnId ?: run { fail("当前会话没有可撤销的任务修改"); return@launch }
        runSuspendCatching {
            withContext(Dispatchers.IO) {
                val changes = changeStore.list(selected.project).filter { it.turnId == turnId && it.sessionId == selected.id }
                require(changes.isNotEmpty()) { "最近任务没有可撤销的文件修改" }
                val fs = workspace(selected.project)
                val reversed = changes.groupBy { fs.resolve(it.path).canonicalPath }.values.map { steps ->
                    FileChange(steps.first().path, steps.last().after, steps.first().before)
                }
                fs.commitAll(reversed)
                changeStore.removeTurn(selected.project, turnId)
                val paths = reversed.joinToString { it.path }
                val notice = "已撤销最近任务的文件修改：$paths。命令、Git 提交及其他外部副作用无法自动回滚，请核对实际状态。"
                val next = selected.copy(
                    lastTurnId = null,
                    wire = JsonArray(selected.wire + buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { add(obj("type" to "input_text", "text" to notice)) })
                    }),
                    messages = selected.messages + Message(role = "assistant", text = notice),
                    updated = System.currentTimeMillis()
                )
                store.save(next)
                next
            }
        }.onSuccess { next ->
            mutable.update { it.copy(session = next, changes = changeStore.list(next.project), usage = "最近任务修改已撤销") }
            refresh()
        }.onFailure { fail("撤销失败：${it.message}") }
    }
    fun fork() = scope.launch { if (!state.value.busy) state.value.session?.let { s -> withContext(Dispatchers.IO) { store.save(s); store.fork(s.id) }.let { mutable.update { old -> old.copy(session = it) }; refresh() } } }
    fun rename(title: String) = updateSession { it.copy(title = title.trim().ifBlank { "新对话" }) }
    fun goal(goal: String) = updateSession { it.copy(goal = goal) }
    private fun updateSession(transform: (Session) -> Session) = scope.launch { if (!state.value.busy) state.value.session?.let { s -> val next = transform(s); withContext(Dispatchers.IO) { store.save(next) }; mutable.update { it.copy(session = next) }; refresh() } }
    fun compactContext() {
        if (state.value.busy) return
        val session = state.value.session ?: return
        if (session.wire.count { (it as? JsonObject)?.string("role") == "user" } < 5) {
            fail("至少需要五轮用户消息才能压缩")
            return
        }
        mutable.update { it.copy(busy = true, tool = "压缩上下文中", error = null) }
        scope.launch {
            active = currentCoroutineContext().job
            try {
                val compacted = withContext(Dispatchers.IO) {
                    ContextCompactor(ResponsesClient(state.value.config)).compact(session) { index, total ->
                        mutable.update { it.copy(tool = "压缩上下文 $index/$total") }
                    }.also { store.save(it) }
                }
                mutable.update { it.copy(session = compacted, usage = "历史上下文已压缩；完整聊天记录保留") }
                refresh()
            } catch (e: CancellationException) {
                fail("上下文压缩已取消，原会话保留")
                throw e
            } catch (e: Exception) {
                fail("上下文压缩失败：${e.message}；原会话保留")
            } finally {
                active = null
                mutable.update { it.copy(busy = false, tool = "") }
            }
        }
    }
    fun saveConfig(config: ApiConfig) = scope.launch {
        runSuspendCatching { withContext(Dispatchers.IO) { settings.save(config) } }.onSuccess { mutable.update { it.copy(config = config, error = null) } }.onFailure { fail("设置保存失败：${it.message}") }
    }
    fun fail(message: String) { mutable.update { it.copy(error = message) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun decide(allow: Boolean) { answer?.complete(allow) }
    private suspend fun approve(value: Approval): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        mutable.update { it.copy(approval = value) }
        return try { deferred.await() } finally { answer = null; mutable.update { it.copy(approval = null) } }
    }
    fun addAttachment(name: String, input: JsonObject) {
        check(!state.value.busy)
        attachmentInputs += input; mutable.update { it.copy(attachments = it.attachments + name) }
    }
    fun clearAttachments() { if (!state.value.busy) { attachmentInputs.clear(); mutable.update { it.copy(attachments = emptyList()) } } }
    private fun record(changes: List<FileChange>, project: String = state.value.project, sessionId: String? = null, turnId: String? = null) {
        changeStore.append(project, changes.map { it.copy(sessionId = sessionId, turnId = turnId) })
        mutable.update { s -> if (s.project == project) s.copy(changes = changeStore.list(project)) else s }
    }
    fun setSkillRoots(roots: List<String>) = scope.launch {
        if (state.value.busy) return@launch
        val project = state.value.project
        runSuspendCatching {
            withContext(Dispatchers.IO) {
                val fs = workspace(project)
                val change = SkillCatalog(fs).previewConfig(roots)
                fs.commit(change)
                record(listOf(change), project)
            }
        }.onFailure { fail("技能目录保存失败：${it.message}") }
    }
    fun steer(text: String) {
        val now = state.value
        val session = now.session
        if (!now.canSteer || session == null) { fail("当前没有可追加指令的对话任务"); return }
        scope.launch {
            runSuspendCatching { withContext(Dispatchers.IO) { steeringInbox.add(session.id, text) } }
                .onSuccess { mutable.update { it.copy(usage = "追加指令已排队，将在当前回复完成后执行") } }
                .onFailure { fail("追加指令保存失败：${it.message}") }
        }
    }
    fun reserveTask(): Boolean {
        val native = (context.applicationContext as CodexApplication).originalCore.state.value
        if ((context.applicationContext as CodexApplication).originalCore.configurationChanging) return false
        if (state.value.busy || native.working || native.closing || native.turnId != null) return false
        mutable.update { it.copy(busy = true) }
        return true
    }
    fun releaseReservation() { mutable.update { it.copy(busy = false) } }
    suspend fun run(prompt: String, terminal: Boolean = false) {
        if (active != null || prompt.isBlank()) return
        active = currentCoroutineContext().job
        val snapshot = state.value
        val initial = (snapshot.session ?: Session(project = snapshot.project)).copy(lastTurnId = UUID.randomUUID().toString())
        mutable.update { it.copy(busy = true, canSteer = !terminal, stream = "", thinking = "", tool = "", error = null) }
        val fs = workspace(snapshot.project)
        val runner = ShellRunner("/system/bin/sh", fs.root, launcher = { NativeShell.start(it, fs.root) }).also { shell = it }
        try {
            if (terminal) {
                if (approve(Approval("执行手机命令", prompt, shell = true))) {
                    mutable.update { it.copy(terminal = "$ ${prompt}\n") }
                    val r = runner.execute(prompt) { chunk -> mutable.update { it.copy(terminal = (it.terminal + chunk).takeLast(256 * 1024)) } }
                    mutable.update { it.copy(terminal = it.terminal + "\n退出码 ${r.exitCode}" + if (r.truncated) " · 输出已截断" else "") }
                }
            } else {
                require(snapshot.config.model.isNotBlank()) { "请先在设置中选择模型并填写 API 密钥" }
                val extras = listOf(GitTools(fs, GitCredentialStore(context)), McpTools(context))
                val tools = LocalTools(fs, runner, extras) { record(it, snapshot.project, initial.id, initial.lastTurnId) }
                val runtime = AgentRuntime(ResponsesClient(snapshot.config), tools, ::approve, steeringInbox, toolJournal)
                val attachments = attachmentInputs.toList()
                withContext(Dispatchers.IO) { runtime.run(initial, prompt, attachments) { event ->
                    when (event) {
                        is AgentEvent.Delta -> mutable.update { s -> if (event.reasoning) s.copy(thinking = (s.thinking + event.text).takeLast(64000)) else s.copy(stream = s.stream + event.text) }
                        is AgentEvent.Tool -> mutable.update { it.copy(tool = if (event.running) "${toolTitle(event.name)} · 处理中" else "", stream = if (event.running) "" else it.stream) }
                        is AgentEvent.Usage -> mutable.update { it.copy(usage = "输入 ${event.input} · 输出 ${event.output}") }
                        is AgentEvent.Updated -> {
                            mutable.update { it.copy(session = event.session, stream = "") }
                            store.save(event.session)
                        }
                    }
                }
                }
                attachmentInputs.clear(); mutable.update { it.copy(attachments = emptyList()) }
            }
        } catch (e: CancellationException) { mutable.update { it.copy(error = "任务已停止；已完成的文件修改仍保留，可在修改记录中查看") }; throw e }
        catch (e: Exception) { fail(e.message ?: "执行失败") }
        finally {
            runner.stop(); shell = null
            withContext(NonCancellable) { refresh() }
            active = null
            mutable.update { it.copy(busy = false, canSteer = false, approval = null, tool = "") }
        }
    }
    private val controlSequences = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    fun startInteractiveTerminal() {
        if (state.value.busy || interactive != null) return
        val project = state.value.project
        scope.launch {
            runSuspendCatching {
                withContext(Dispatchers.IO) { NativePty.start(workspace(project).root) }
            }.onSuccess { pty ->
                interactive = pty
                mutable.update { it.copy(interactiveTerminal = true, terminal = "已连接当前项目的交互式终端。\n") }
                scope.launch(Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = pty.input.read(buffer)
                            if (count < 0) break
                            val cleaned = controlSequences.replace(String(buffer, 0, count, Charsets.UTF_8), "").replace("\r", "")
                            mutable.update { it.copy(terminal = (it.terminal + cleaned).takeLast(256 * 1024)) }
                        }
                    } catch (e: java.io.IOException) {
                        if (interactive === pty) fail("终端连接中断：${e.message}")
                    } finally {
                        pty.close()
                        if (interactive === pty) {
                            interactive = null
                            mutable.update { it.copy(interactiveTerminal = false) }
                        }
                    }
                }
            }.onFailure { fail("启动交互式终端失败：${it.message}") }
        }
    }
    fun writeInteractiveTerminal(text: String) {
        val pty = interactive ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { pty.send(text) }.onFailure { fail("终端写入失败：${it.message}") }
        }
    }
    fun resizeInteractiveTerminal(rows: Int, columns: Int) {
        val pty = interactive ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { pty.resize(rows, columns) }.onFailure { fail("终端调整尺寸失败：${it.message}") }
        }
    }
    fun closeInteractiveTerminal() {
        val pty = interactive ?: return
        interactive = null
        mutable.update { it.copy(interactiveTerminal = false) }
        scope.launch(Dispatchers.IO) { pty.close() }
    }
    fun stop() { answer?.cancel(); shell?.stop(); active?.cancel() }
    suspend fun writeFile(path: String, text: String, project: String = state.value.project, expectedContent: String? = null) = withContext(Dispatchers.IO) {
        check(!state.value.busy) { "任务正在运行，请先停止后编辑" }
        val fs = workspace(project); val c = FileChange(path, expectedContent, text); fs.commit(c); record(listOf(c), project)
    }
    suspend fun undo(change: FileChange) = withContext(Dispatchers.IO) {
        check(!state.value.busy) { "任务正在运行" }
        workspace().commit(FileChange(change.path, change.after, change.before))
        changeStore.remove(state.value.project, change)
        mutable.update { it.copy(changes = changeStore.list(state.value.project)) }
    }
}
