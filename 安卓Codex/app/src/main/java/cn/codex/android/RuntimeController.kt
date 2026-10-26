package cn.codex.android

import android.content.Context
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.io.File

// 前台服务持有任务；旋转、切换页面不会重建或重放正在执行的工作。
data class ScreenState(
    val config: ApiConfig = ApiConfig(), val session: Session? = null,
    val sessions: List<SessionSummary> = emptyList(), val projects: List<String> = emptyList(),
    val project: String = "默认项目", val busy: Boolean = false, val stream: String = "",
    val thinking: String = "", val tool: String = "", val terminal: String = "",
    val approval: Approval? = null, val error: String? = null, val usage: String = "",
    val changes: List<FileChange> = emptyList(), val attachments: List<String> = emptyList()
)
class RuntimeController(private val context: Context) {
    private val settings = SecureSettings(context)
    private val home = File(context.filesDir, "工作区").apply { mkdirs() }
    val store = SessionStore(File(context.filesDir, "会话"))
    private val changeStore = ChangeStore(File(context.filesDir, "修改历史"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> fail("数据操作失败：${error.message}") })
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var shell: ShellRunner? = null
    private val attachmentInputs = mutableListOf<JsonObject>()
    init {
        scope.launch {
            val config = withContext(Dispatchers.IO) { runCatching { settings.load() } }
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
        val data = withContext(Dispatchers.IO) { home.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty() to store.list(includeArchived = true) }
        mutable.update { it.copy(projects = data.first, sessions = data.second) }
    }
    fun newSession(project: String = state.value.project) {
        if (state.value.busy) return
        workspace(project)
        mutable.update { it.copy(project = project, session = Session(project = project), stream = "", thinking = "", error = null, attachments = emptyList(), changes = emptyList()) }
        attachmentInputs.clear()
        scope.launch { val p = state.value.project; val changes = withContext(Dispatchers.IO) { changeStore.list(p) }; mutable.update { it.copy(changes = changes) }; refresh() }
    }
    fun openSession(id: String) { if (!state.value.busy) scope.launch { runCatching { withContext(Dispatchers.IO) { store.load(id) } }.onSuccess { s -> mutable.update { it.copy(session = s, project = s.project, stream = "", thinking = "", error = null, attachments = emptyList(), changes = emptyList()) }; attachmentInputs.clear(); mutable.update { it.copy(changes = changeStore.list(s.project)) } }.onFailure { fail(it.message ?: "会话读取失败") } } }
    fun archive(id: String) = scope.launch { if (!state.value.busy) runCatching { withContext(Dispatchers.IO) { val s = store.load(id); store.save(s.copy(archived = !s.archived)) }; refresh() }.onFailure { fail(it.message.orEmpty()) } }
    fun fork() = scope.launch { if (!state.value.busy) state.value.session?.let { s -> withContext(Dispatchers.IO) { store.save(s); store.fork(s.id) }.let { mutable.update { old -> old.copy(session = it) }; refresh() } } }
    fun rename(title: String) = updateSession { it.copy(title = title.trim().ifBlank { "新对话" }) }
    fun goal(goal: String) = updateSession { it.copy(goal = goal) }
    private fun updateSession(transform: (Session) -> Session) = scope.launch { if (!state.value.busy) state.value.session?.let { s -> val next = transform(s); withContext(Dispatchers.IO) { store.save(next) }; mutable.update { it.copy(session = next) }; refresh() } }
    fun saveConfig(config: ApiConfig) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { settings.save(config) } }.onSuccess { mutable.update { it.copy(config = config, error = null) } }.onFailure { fail("设置保存失败：${it.message}") }
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
    private fun record(changes: List<FileChange>) {
        val project = state.value.project
        changeStore.append(project, changes)
        mutable.update { s -> s.copy(changes = changeStore.list(project)) }
    }
    fun reserveTask(): Boolean {
        if (state.value.busy) return false
        mutable.update { it.copy(busy = true) }
        return true
    }
    fun releaseReservation() { mutable.update { it.copy(busy = false) } }
    suspend fun run(prompt: String, terminal: Boolean = false) {
        if (active != null || prompt.isBlank()) return
        active = currentCoroutineContext().job
        val snapshot = state.value
        val initial = snapshot.session ?: Session(project = snapshot.project)
        mutable.update { it.copy(busy = true, stream = "", thinking = "", tool = "", error = null) }
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
                val extras = listOf(GitTools(fs), McpTools(context))
                val tools = LocalTools(fs, runner, extras, ::record)
                val runtime = AgentRuntime(ResponsesClient(snapshot.config), tools, ::approve)
                val attachments = attachmentInputs.toList()
                withContext(Dispatchers.IO) { runtime.run(initial, prompt, attachments) { event ->
                    when (event) {
                        is AgentEvent.Delta -> mutable.update { s -> if (event.reasoning) s.copy(thinking = (s.thinking + event.text).takeLast(64000)) else s.copy(stream = s.stream + event.text) }
                        is AgentEvent.Tool -> mutable.update { it.copy(tool = if (event.running) "${event.name} · 处理中" else "", stream = if (event.running) "" else it.stream) }
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
            mutable.update { it.copy(busy = false, approval = null, tool = "") }
        }
    }
    fun stop() { answer?.cancel(); shell?.stop(); active?.cancel() }
    suspend fun writeFile(path: String, text: String) = withContext(Dispatchers.IO) {
        check(!state.value.busy) { "任务正在运行，请先停止后编辑" }
        val fs = workspace(); val c = fs.previewWrite(path, text); fs.commit(c); record(listOf(c))
    }
    suspend fun undo(change: FileChange) = withContext(Dispatchers.IO) {
        check(!state.value.busy) { "任务正在运行" }
        workspace().commit(FileChange(change.path, change.after, change.before))
        changeStore.remove(state.value.project, change)
        mutable.update { it.copy(changes = changeStore.list(state.value.project)) }
    }
}
