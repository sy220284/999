package cn.codex.android

import android.content.Context
import android.content.Intent
import cn.codex.core.*
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Application 持有连接；Compose 只订阅状态。原版任务由独立前台服务等待完成。 */
data class OriginalState(
    val connectionEpoch: Long = 0, val connected: Boolean = false, val closing: Boolean = false, val loading: Boolean = false, val working: Boolean = false,
    val threadId: String? = null, val turnId: String? = null,
    val threads: List<JsonObject> = emptyList(), val nextCursor: String? = null, val archived: Boolean = false,
    val items: List<OriginalItem> = emptyList(), val requests: List<ServerMessage.Request> = emptyList(),
    val account: String = "尚未读取账号", val error: String? = null, val status: String = "未启动原版核心",
    val loginUrl: String? = null, val loginCode: String? = null, val loginId: String? = null,
    val historyCursor: String? = null,
    val mcpAuthorizationUrl: String? = null, val mcpAuthorizationName: String? = null, val mcpLoginId: String? = null,
    val agent: JsonObject? = null, val agentItems: List<OriginalItem> = emptyList(), val agentCursor: String? = null,
    val goal: JsonObject? = null, val attachments: List<JsonObject> = emptyList(), val attachmentCursor: String? = null,
    val threadGitInfo: JsonObject? = null,
    val loadedThreads: OriginalSessionPage<String> = OriginalSessionPage(),
    val attachmentOwners: OriginalSessionPage<JsonObject> = OriginalSessionPage(),
    val ownerType: String = "", val ownerKey: String = "",
    val threadStatuses: Map<String, JsonObject> = emptyMap(),
    val guardianReviews: List<JsonObject> = emptyList(),
    val turnProgress: Map<String, OriginalTurnProgress> = emptyMap(),
    val threadUpdates: OriginalThreadUpdatesState = OriginalThreadUpdatesState(), val threadListVersion: Long = 0,
    val turnNotificationVersion:Long=0,val queueMonitoring:Boolean=false,
    val events: OriginalEventState = OriginalEventState(), val toolBusy: Boolean = false,
    val sections: List<JsonObject> = emptyList(), val sectionCursor: String? = null,
    val turns: List<JsonObject> = emptyList(), val turnCursor: String? = null,
    val models: List<String> = emptyList(), val model: String = "", val report: String = ""
)
class OriginalCoreHost(
    private val context: Context,
    private val engineFactory: OriginalEngineFactory = NativeOriginalEngineFactory,
    private val configLoader: suspend () -> ApiConfig = { withContext(Dispatchers.IO) { SecureSettings(context).load() } },
    private val startupTimeoutMs: Long = 60_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val startTask: (String) -> Unit = { prompt -> context.startForegroundService(Intent(context, OriginalExecutionService::class.java).putExtra("prompt", prompt)); Unit },
    private val otherTaskRunning: () -> Boolean = { (context.applicationContext as CodexApplication).controller.state.value.busy }
) {
    private val mutable = MutableStateFlow(OriginalState())
    val state = mutable.asStateFlow()
    private val startup = Mutex()
    private val lifecycle = OriginalRuntimeLifecycle()
    private var engine: OriginalEngineSession? = null
    private val connection get() = engine?.connection
    private var startupJob: Job? = null
    private var client: OriginalCodexClient? = null
    private var listener: Job? = null
    private var closingJob: Job? = null
    private var operationJob: Job? = null
    private class ThreadUnavailable(message: String) : IllegalStateException(message)
    fun selectedThreadUsable() = OriginalThreadUpdates.canUse(state.value.threadUpdates.threads[state.value.threadId])
    private data class Outcome(val status: String, val error: String?)
    private data class TurnWaiter(val epoch: Long, val threadId: String, val requestId: String,
        val invalidated: CompletableDeferred<JsonElement> = CompletableDeferred(), val finished: CompletableDeferred<Unit> = CompletableDeferred(), val observedStart: CompletableDeferred<String> = CompletableDeferred(), var turnId: String? = null,
        val outcomes: LinkedHashMap<String, Outcome> = linkedMapOf())
    private var turnWaiter: TurnWaiter? = null
    private var reservedTask: OriginalTurnTask? = null
    private var stopping: Job? = null
    private val remainingEvents = OriginalRemainingEvents()
    val realtimeAudio = OriginalRealtimeAudio(context,scope)
    private val workspace = File(context.filesDir, "工作区/原版项目").apply { mkdirs() }
    private val androidTools = OriginalAndroidTools(workspace)
    private val toolJobs = mutableMapOf<ServerMessage.Request,Job>()
    private val engineHome = File(context.filesDir, "原版核心数据").apply { mkdirs() }
    val configurationJournal get() = (context.applicationContext as CodexApplication).configurationJournal
    val extensions: OriginalExtensionsController = OriginalExtensionsController(scope, workspace.absolutePath, configurationJournal.lifecycle("插件、技能与自动规则")) { state.value.working || state.value.turnId != null || state.value.loading || otherTaskRunning() || managedChanging || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || commands.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running }
    val mcp: OriginalMcpController = OriginalMcpController(scope, workspace.absolutePath,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || managedChanging || extensions.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || commands.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running },
        threadId = { state.value.threadId }, mutationLifecycle = configurationJournal.lifecycle("MCP 服务操作"))
    val commands: OriginalCommandController = OriginalCommandController(scope,
        busyElsewhere = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || managedChanging || extensions.state.value.mutating || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || (context.applicationContext as CodexApplication).fullTerminal.state.value.running },
        lifecycle = configurationJournal.lifecycle("原版独立命令"))
    val settings: OriginalSettingsController = OriginalSettingsController(scope, workspace.absolutePath,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging },
        threadId = { state.value.threadId }, lifecycle = configurationJournal.lifecycle("原版实验设置"))
    val files: OriginalFilesController = OriginalFilesController(scope, workspace,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging },
        lifecycle = configurationJournal.lifecycle("原版项目文件"))
    val library: OriginalLibraryController = OriginalLibraryController(scope, workspace.absolutePath, { state.value.threadId },
        { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging }, configurationJournal.lifecycle("市场、分享与应用目录"))
    val services: OriginalServicesController = OriginalServicesController(scope, { state.value.threadId },
        { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging }, configurationJournal.lifecycle("账号授权与反馈"), CreditResetRequestStore(context))
    val migration: OriginalMigrationController = OriginalMigrationController(scope, workspace.absolutePath,
        { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging }, configurationJournal.lifecycle("配置与迁移"))
    val queue=OriginalQueueController(scope,{state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging},configurationJournal.lifecycle("待办队列变更"))
    private fun completionBusy()=state.value.working || state.value.turnId!=null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging
    val projects=OriginalProjectsController(scope,{completionBusy()},configurationJournal.lifecycle("项目管理"),ProjectCreationRequestStore(context))
    val history=OriginalHistoryController(scope,{state.value.threadId},{completionBusy()},configurationJournal.lifecycle("会话记录与后台终端"))
    val advanced=OriginalAdvancedController(scope,{state.value.threadId},workspace.absolutePath,{completionBusy()},configurationJournal.lifecycle("会话设置、记忆与采样"))
    val connections=OriginalConnectionsController(scope,{state.value.threadId},{completionBusy()},configurationJournal.lifecycle("环境、远控与服务认证"))
    val search=OriginalSearchController(scope,workspace.absolutePath)
    val realtime=OriginalRealtimeController(scope,{state.value.working || state.value.turnId!=null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging},configurationJournal.lifecycle("实时会话"))
    val microphone=OriginalRealtimeMicrophone(context,scope){bytes -> realtime.sendAudio(bytes)}
    fun commandWorkspace() = workspace.absolutePath
    val managedChanging get() = projects.operations.state.value.mutating || history.operations.state.value.mutating || advanced.operations.state.value.mutating || connections.operations.state.value.mutating || queue.operations.state.value.mutating || realtime.state.value.running || library.operations.state.value.mutating || services.operations.state.value.mutating || migration.operations.state.value.mutating
    val configurationChanging get() = state.value.toolBusy || (context.applicationContext as CodexApplication).gitOperations.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running || commands.state.value.running || extensions.state.value.mutating || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || managedChanging
    fun mcpImports() = McpTools(context).connections().map { it.name to it.url }
    private fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: "原版核心操作失败") } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun operation(block: suspend (OriginalCodexClient) -> Unit): Job? {
        if (state.value.loading || state.value.closing || configurationChanging) return null
        val api = client ?: run { error(IllegalStateException("请先启动原版核心")); return null }
        mutable.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try { block(api) } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) { if (client === api) error(IllegalStateException("原版请求超时，请刷新实际状态；不会自动重放操作")) }
                else throw cancelled
            } catch (failure: Exception) { if (client === api) error(failure) } finally { if (client === api) mutable.update { it.copy(loading = false) } }
        }.also { operationJob = it }
    }
    private fun current(epoch: Long, rpc: AppServerConnection? = connection) = lifecycle.isCurrent(epoch) && connection === rpc
    private fun ensureCurrent(epoch: Long, rpc: AppServerConnection) { check(current(epoch, rpc)) { "原版连接已变更，已丢弃旧操作结果" } }
    private fun syncLifecycle() { val value = lifecycle.state; mutable.update { it.copy(threadId = value.threadId, turnId = value.turnId) } }
    fun connect(): Job? {
        if (state.value.loading || state.value.connected || state.value.closing) return null
        val epoch = lifecycle.beginConnection()
        mutable.update { it.copy(connectionEpoch = epoch, loading = true, error = null) }
        return scope.launch {
            try { withTimeout(startupTimeoutMs) { startup.withLock { closingJob?.join(); startProcess(epoch) } } } catch (cancelled: CancellationException) {
                if (lifecycle.isCurrent(epoch)) {
                    closeSession(cancelStartup = false)
                    if (cancelled is TimeoutCancellationException) error(IllegalStateException("原版核心启动超时，已关闭启动中的进程，请核查后重试"))
                }
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (failure: Exception) { if (lifecycle.isCurrent(epoch)) { closeSession(cancelStartup = false); error(failure) } } finally { if (lifecycle.isCurrent(epoch)) mutable.update { it.copy(loading = false) } }
        }.also { startupJob = it }
    }
    private suspend fun startProcess(epoch: Long) {
        val config = configLoader()
        val session = engineFactory.open(OriginalEngineLaunch(context, config, workspace, engineHome, scope))
        if (!lifecycle.isCurrent(epoch)) {
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
            throw CancellationException("启动期间连接已关闭")
        }
        engine = session
        val rpc = session.connection
        listener = scope.launch {
            try { rpc.messages.collect { message -> if (current(epoch, rpc)) onMessage(epoch, rpc, message) } } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (current(epoch, rpc)) { close(); error(IllegalStateException("原版核心连接已中断，请重新启动后恢复会话")) } }
        }
        val api = OriginalCodexClient(rpc)
        api.initialize()
        ensureCurrent(epoch, rpc)
        if (config.apiKey.isNotBlank() && config.endpoint.trimEnd('/') == "https://api.openai.com/v1" && api.account().jsonObject["account"] == JsonNull) api.loginApiKey(config.apiKey)
        ensureCurrent(epoch, rpc)
        client = api;projects.bind(api);history.bind(api);advanced.bind(api);connections.bind(api);queue.bind(api); search.bind(api);realtime.bind(api);extensions.bind(api); mcp.bind(api); commands.bind(api); settings.bind(api); files.bind(api); library.bind(api); services.bind(api); migration.bind(api)
        mutable.update { it.copy(connected = true, status = "原版核心已连接 · 凭证由手机 Keystore 加密保存", model = config.model) }
        refreshThreads(api, false); readAccount(api, epoch)
    }
    fun close() { closeSession(cancelStartup = true) }
    private fun closeSession(cancelStartup: Boolean) {
        if (state.value.closing) return
        lifecycle.disconnect(lifecycle.state.connectionEpoch)
        microphone.stop();realtimeAudio.stop();projects.bind(null);history.bind(null);advanced.bind(null);connections.bind(null);queue.bind(null);search.bind(null);realtime.bind(null);remainingEvents.reset();toolJobs.values.toList().forEach { it.cancel() };toolJobs.clear()
        if (cancelStartup) startupJob?.cancel()
        operationJob?.cancel(); operationJob = null
        reservedTask = null
        stopping?.cancel(); stopping = null
        extensions.bind(null); mcp.bind(null); commands.bind(null); settings.bind(null); files.bind(null); library.bind(null); services.bind(null); migration.bind(null)
        turnWaiter?.finished?.completeExceptionally(IllegalStateException("原版核心已停止")); turnWaiter = null
        listener?.cancel(); listener = null
        val session = engine
        engine = null; client = null
        mutable.update { OriginalState(connectionEpoch = lifecycle.state.connectionEpoch, model = it.model, closing = true, status = "正在关闭核心") }
        closingJob = scope.launch(Dispatchers.IO) {
            try { session?.close() } catch (_: Exception) { mutable.update { it.copy(error = "核心资源回收未能确认，请检查进程状态后再启动") } } finally { mutable.update { it.copy(closing = false, status = "原版核心已关闭") } }
        }
    }
    fun refresh(archived: Boolean = state.value.archived, more: Boolean = false) = operation { api -> refreshThreads(api, archived, more) }
    private suspend fun refreshThreads(api: OriginalCodexClient, archived: Boolean, more: Boolean = false) {
        val cursor = if (more) state.value.nextCursor ?: return else null
        val before = state.value.threadUpdates.version
        val response = api.threads(cursor, archived).jsonObject
        ensureCurrent(lifecycle.state.connectionEpoch, api.connection)
        val entries = (response["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        mutable.update { it.copy(threads = OriginalThreadUpdates.overlay((if (more) it.threads + entries else entries).distinctBy { row -> row.string("id") }, it.threadUpdates, archived, before), threadListVersion = before, nextCursor = response.string("nextCursor").ifBlank { null }, archived = archived) }
    }
    fun selectModel(model: String) { mutable.update { it.copy(model = model) } }
    fun loadModels() = operation { api ->
        val found = mutableListOf<String>(); var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = api.models(cursor).jsonObject
            found += (page["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("model")?.takeIf(String::isNotBlank) }
            cursor = page.string("nextCursor").ifBlank { null }
        } while (cursor != null && seen.add(cursor))
        mutable.update { it.copy(models = found.distinct()) }
    }
    fun newThread() = operation { api ->
        check(!state.value.working && state.value.turnId == null)
        val thread = api.start(workspace.absolutePath, state.value.model).jsonObject.getValue("thread").jsonObject
        selectThread(thread); refreshThreads(api, false)
    }
    fun openThread(id: String) = operation { api ->
        check(!state.value.working && state.value.turnId == null)
        val before = state.value.threadUpdates.version
        val response = api.resume(id).jsonObject
        selectThread(response.getValue("thread").jsonObject, before)
        mutable.update { it.copy(historyCursor = response.string("itemsBackwardsCursor").ifBlank { null }) }
    }
    private fun selectThread(thread: JsonObject, throughVersion: Long = state.value.threadUpdates.version) {
        microphone.stop();realtimeAudio.stop();queue.selectThread(thread.string("id"))
        val turns = (thread["turns"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val active = turns.lastOrNull { it.string("status") == "inProgress" }?.string("id")
        val items = OriginalThreadView.history(thread)
        lifecycle.accept(lifecycle.state.connectionEpoch, OriginalLifecycleEvent.ThreadSelected(thread.string("id"), active))
        syncLifecycle()
        mutable.update { it.copy(items = items, turnProgress = emptyMap(), threadUpdates = OriginalThreadUpdates.acknowledgeHistory(it.threadUpdates, thread.string("id"), throughVersion), threadGitInfo = thread["gitInfo"] as? JsonObject, attachmentOwners = OriginalSessionPage(), ownerType = "", ownerKey = "", goal = null, attachments = emptyList(), attachmentCursor = null, turns = emptyList(), turnCursor = null, historyCursor = null, model = thread.string("model", it.model), status = if (lifecycle.state.turnId == null) "会话已恢复" else "已恢复正在运行的回合") }
    }
    fun loadHistory() = operation { api ->
        val snapshot = state.value
        val cursor = snapshot.historyCursor ?: return@operation
        val page = api.history(requireNotNull(snapshot.threadId), cursor).jsonObject
        val older = (page["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("item") as? JsonObject }.map(OriginalThreadView::item).reversed()
        mutable.update { it.copy(items = (older + it.items).distinctBy { item -> item.id }, historyCursor = page.string("nextCursor").ifBlank { null }) }
    }
    fun fork() = operation { api -> check(!state.value.working && state.value.turnId == null); selectThread(api.fork(requireNotNull(state.value.threadId)).jsonObject.getValue("thread").jsonObject); refreshThreads(api, false) }
    fun rename(title: String) = operation { api -> api.rename(requireNotNull(state.value.threadId), title); refreshThreads(api, state.value.archived) }
    fun archive(id: String, archived: Boolean) = operation { api -> check(!state.value.working && state.value.turnId == null); api.archive(id, archived); refreshThreads(api, state.value.archived) }
    fun compact() = operation { it.compact(requireNotNull(state.value.threadId)) }
    fun loadLoadedThreads(more: Boolean = false) = operation { api ->
        val base: OriginalSessionPage<String> = if (more) state.value.loadedThreads else OriginalSessionPage()
        val cursor = if (more) base.nextCursor ?: return@operation else null
        val response = api.loadedThreads(cursor).jsonObject
        val ids = response.getValue("data").jsonArray.map { item -> val id = item.jsonPrimitive; require(id.isString && id.content.isNotBlank()) { "已加载会话缺少有效标识" }; id.content }
        val page = base.append(ids, response.string("nextCursor").ifBlank { null }, cursor) { it }
        mutable.update { it.copy(loadedThreads = page, error = if (page.repeatedCursor) "已加载会话游标重复，已停止继续分页" else null) }
    }
    fun loadAttachmentOwners(type: String, key: String, more: Boolean = false) = operation { api ->
        require(type.isNotBlank() && key.isNotBlank()) { "请填写资料类型与标识" }
        val base: OriginalSessionPage<JsonObject> = if (more) { check(type == state.value.ownerType && key == state.value.ownerKey) { "资料查询已变化，请重新查找" }; state.value.attachmentOwners } else OriginalSessionPage()
        val cursor = if (more) base.nextCursor ?: return@operation else null
        val response = api.attachmentOwners(type, key, cursor).jsonObject
        val owners = objects(response)
        require(owners.all { it.string("threadId").isNotBlank() }) { "资料所属会话缺少标识" }
        val page = base.append(owners, response.string("nextCursor").ifBlank { null }, cursor) { it.string("threadId") }
        mutable.update { it.copy(attachmentOwners = page, ownerType = type, ownerKey = key, error = if (page.repeatedCursor) "资料所属会话游标重复，已停止继续分页" else null) }
    }
    fun loadGitMetadata() = operation { api ->
        val id = requireNotNull(state.value.threadId)
        val thread = api.read(id, includeTurns = false).jsonObject.getValue("thread").jsonObject
        check(thread.string("id") == id) { "返回的会话标识不同" }
        mutable.update { it.copy(threadGitInfo = thread["gitInfo"] as? JsonObject) }
    }
    fun saveGitMetadata(fields: Map<String, OriginalMetadataEdit>) = operation { api ->
        val id = idleThread(); val patch = OriginalSessionActions.gitPatch(fields)
        val tracking = configurationJournal.lifecycle("原版会话 Git 资料")
        val ticket = tracking.begin(); var confirmed = false
        try {
            val thread = api.updateThreadGit(id, patch).jsonObject.getValue("thread").jsonObject
            check(thread.string("id") == id) { "返回的会话标识不同，请重新读取核查" }
            mutable.update { it.copy(threadGitInfo = thread["gitInfo"] as? JsonObject, status = "已保存会话 Git 资料") }
            confirmed = true
        } finally { tracking.finish(ticket, confirmed) }
    }
    fun inspectAgent(threadId: String) = operation { api ->
        val thread = api.read(threadId).jsonObject.getValue("thread").jsonObject
        mutable.update { it.copy(agent = thread, agentItems = OriginalThreadView.history(thread), agentCursor = null) }
    }
    fun closeAgent() { mutable.update { it.copy(agent = null, agentItems = emptyList(), agentCursor = null) } }
    fun loadAgentTurns(more: Boolean = false) = operation { api ->
        val id = state.value.agent?.string("id") ?: return@operation
        val cursor = if (more) state.value.agentCursor ?: return@operation else null
        val page = api.turns(id, cursor).jsonObject
        val items = objects(page).reversed().flatMap { turn -> (turn["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map(OriginalThreadView::item) }
        if (state.value.agent?.string("id") == id) mutable.update { it.copy(agentItems = ((if (more) items + it.agentItems else items)).distinctBy { item -> item.id }, agentCursor = page.string("nextCursor").ifBlank { null }) }
    }
    private fun objects(page: JsonObject) = (page["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun idleThread(): String {
        check(!state.value.working && state.value.turnId == null && !otherTaskRunning()) { "请先停止任务" }
        check(selectedThreadUsable()) { "会话状态已变化，请先重新打开或恢复会话" }
        return requireNotNull(state.value.threadId) { "请先选择会话" }
    }
    fun loadGoal() = operation { api -> val thread = requireNotNull(state.value.threadId); val goal = api.goal(thread).jsonObject["goal"] as? JsonObject; if (state.value.threadId == thread) mutable.update { it.copy(goal = goal) } }
    fun saveGoal(objective: String, status: String, budget: Long?) = operation { api ->
        val thread = idleThread(); require(objective.isNotBlank()) { "请填写目标" }
        val goal = api.setGoal(thread, objective, status, budget).jsonObject["goal"] as? JsonObject
        mutable.update { it.copy(goal = goal, status = "目标已更新") }
    }
    fun clearGoal() = operation { api -> api.clearGoal(idleThread()); mutable.update { it.copy(goal = null, status = "目标已清除") } }
    fun loadAttachments(more: Boolean = false) = operation { api ->
        val before = state.value.threadUpdates.version
        val thread = requireNotNull(state.value.threadId); val cursor = if (more) state.value.attachmentCursor ?: return@operation else null
        val page = api.attachments(thread, cursor).jsonObject
        if (state.value.threadId == thread) mutable.update { it.copy(threadUpdates = if (more) it.threadUpdates else OriginalThreadUpdates.acknowledgeAttachments(it.threadUpdates, thread, before), attachments = ((if (more) it.attachments else emptyList()) + objects(page)).distinctBy { row -> row.string("id") }, attachmentCursor = page.string("nextCursor").ifBlank { null }) }
    }
    fun addAttachment(type: String, key: String, payload: JsonElement) = operation { api ->
        val thread = idleThread(); require(type.isNotBlank() && key.isNotBlank()) { "请填写资料类型与标识" }
        api.addAttachment(thread, type, key, payload)
        val page = api.attachments(thread).jsonObject
        mutable.update { it.copy(attachments = objects(page), attachmentCursor = page.string("nextCursor").ifBlank { null }, status = "资料记录已保存") }
    }
    fun removeAttachment(type: String, key: String) = operation { api ->
        val thread = idleThread(); api.removeAttachment(thread, type, key)
        val page = api.attachments(thread).jsonObject
        mutable.update { it.copy(attachments = objects(page), attachmentCursor = page.string("nextCursor").ifBlank { null }) }
    }
    private suspend fun readSections(api: OriginalCodexClient, more: Boolean = false) {
        val cursor = if (more) state.value.sectionCursor ?: return else null
        val page = api.sections(cursor).jsonObject
        mutable.update { it.copy(sections = ((if (more) it.sections else emptyList()) + objects(page)).distinctBy { row -> row.string("id") }, sectionCursor = page.string("nextCursor").ifBlank { null }) }
    }
    fun loadSections(more: Boolean = false) = operation { readSections(it, more) }
    fun saveSection(sectionId: String?, name: String) = operation { api ->
        require(name.isNotBlank()) { "请填写分区名称" }
        if (sectionId == null) api.createSection(name) else api.updateSection(sectionId, name)
        readSections(api)
    }
    fun deleteSection(sectionId: String) = operation { api -> api.deleteSection(sectionId); readSections(api); refreshThreads(api, state.value.archived) }
    fun moveSection(sectionId: String?, beforeThreadId: String? = null) = operation { api -> api.moveSection(idleThread(), sectionId, beforeThreadId); refreshThreads(api, state.value.archived) }
    fun loadTurns(more: Boolean = false) = operation { api ->
        val thread = requireNotNull(state.value.threadId); val cursor = if (more) state.value.turnCursor ?: return@operation else null
        val page = api.turns(thread, cursor).jsonObject
        mutable.update { it.copy(turns = ((if (more) it.turns else emptyList()) + objects(page)).distinctBy { row -> row.string("id") }, turnCursor = page.string("nextCursor").ifBlank { null }) }
    }
    fun revert(beforeTurnId: String) = operation { api ->
        val thread = idleThread(); val result = api.revert(thread, beforeTurnId).jsonObject
        selectThread(result.getValue("thread").jsonObject)
        val cursor = result.string("itemsBackwardsCursor").ifBlank { null }
        if (cursor != null) {
            val page = api.history(thread, cursor).jsonObject
            val items = objects(page).mapNotNull { it["item"] as? JsonObject }.map(OriginalThreadView::item).reversed()
            mutable.update { it.copy(items = items, historyCursor = page.string("nextCursor").ifBlank { null }) }
        }
        val page = api.turns(thread).jsonObject
        mutable.update { it.copy(turns = objects(page), turnCursor = page.string("nextCursor").ifBlank { null }, status = "聊天记录已回退，项目文件未撤销") }
        refreshThreads(api, state.value.archived)
    }
    fun removeThread(delete: Boolean) = operation { api ->
        val thread = idleThread()
        if (delete) api.deleteThread(thread) else api.unsubscribe(thread)
        lifecycle.accept(lifecycle.state.connectionEpoch, OriginalLifecycleEvent.ThreadCleared); syncLifecycle();queue.selectThread(null)
        mutable.update { it.copy(items = emptyList(), turnProgress = emptyMap(), goal = null, attachments = emptyList(), turns = emptyList(), historyCursor = null, attachmentCursor = null, turnCursor = null, status = if (delete) "会话已删除" else "已关闭当前会话视图") }
        refreshThreads(api, state.value.archived)
    }
    fun inspect(kind: String) = operation { api ->
        val data = when (kind) { "配额" -> api.rateLimits(); "技能" -> api.skills(workspace.absolutePath); "MCP" -> api.mcp(); "钩子" -> api.hooks(workspace.absolutePath, state.value.threadId); "插件" -> api.installedPlugins(workspace.absolutePath); else -> throw IllegalArgumentException("未知检查类型") }
        mutable.update { it.copy(report = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data)) }
    }
    fun login(deviceCode: Boolean) = operation { api ->
        val result = (if (deviceCode) api.loginDeviceCode() else api.loginBrowser()).jsonObject
        mutable.update { it.copy(loginId = result.string("loginId").ifBlank { null }, loginUrl = result.string(if (deviceCode) "verificationUrl" else "authUrl").ifBlank { null }, loginCode = result.string("userCode").ifBlank { null }) }
    }
    fun cancelLogin() = operation { api -> state.value.loginId?.let { api.cancelLogin(it) }; mutable.update { it.copy(loginId = null, loginUrl = null, loginCode = null) } }
    fun loginSavedApiKey() = operation { api ->
        check(!state.value.working && state.value.turnId == null)
        val key = configLoader().apiKey
        require(key.isNotBlank()) { "请先在连接设置中保存 API 密钥" }
        api.loginApiKey(key); readAccount(api)
    }
    fun startMcpLogin(name: String) = operation { api ->
        require(name.isNotBlank()); check(state.value.mcpAuthorizationUrl == null) { "已有 MCP 授权正在等待处理" }
        val result = api.mcpLogin(name.trim(), state.value.threadId).jsonObject
        mutable.update { it.copy(mcpAuthorizationUrl = result.string("authorizationUrl").ifBlank { null }, mcpAuthorizationName = name.trim(), mcpLoginId = result.string("loginId").ifBlank { null }) }
    }
    fun dismissMcpLogin() { mutable.update { it.copy(mcpAuthorizationUrl = null) } }
    fun logout() = operation { api -> check(!state.value.working && state.value.turnId == null); api.logout(); readAccount(api) }
    private suspend fun readAccount(api: OriginalCodexClient, epoch: Long = lifecycle.state.connectionEpoch) {
        val account = api.account().jsonObject["account"] as? JsonObject
        val label = when (account?.string("type")) { "chatgpt" -> "ChatGPT · ${account.string("email")} · ${account.string("planType")}"; "apiKey" -> "API 密钥"; else -> "尚未登录" }
        if (current(epoch, api.connection) && client === api) mutable.update { it.copy(account = label) }
    }
    fun send(text: String) {
        if (text.isBlank()) return
        reserve(OriginalTurnTask.Prompt(text), text)
    }
    fun shellCommand(command: String, timeout: String) {
        try {
            require(command.isNotBlank()) { "请填写命令" }
            val limit = timeout.takeIf { it.isNotBlank() }?.toLong()?.also { require(it >= 0) { "超时不能为负数；0 表示立即超时" } }
            reserve(OriginalTurnTask.Shell(requireNotNull(state.value.threadId), command, limit), "会话 shell 命令")
        } catch (failure: Exception) { error(failure) }
    }
    fun injectItems(text: String) = operation { api ->
        val thread = idleThread(); val items = withContext(Dispatchers.Default) { OriginalRemainingContracts.history(text) }
        val tracking = configurationJournal.lifecycle("补入模型历史"); val ticket = tracking.begin(); var confirmed = false
        try { api.injectHistory(thread, items); confirmed = true; mutable.update { it.copy(status = "历史补入请求已返回，内容供后续模型回合使用") } } finally { tracking.finish(ticket, confirmed) }
    }
    fun approveGuardian(notification: JsonObject) = operation { api ->
        val thread = idleThread()
        check(notification.string("threadId") == thread && state.value.guardianReviews.any { it == notification }) { "拒绝事件已变化，请重新核查" }
        val event = OriginalRemainingContracts.guardianEvent(notification)
        val tracking = configurationJournal.lifecycle("人工批准守护拒绝动作"); val ticket = tracking.begin(); var confirmed = false
        try {
            api.approveGuardian(thread, event); confirmed = true
            mutable.update { it.copy(guardianReviews = it.guardianReviews.filterNot { row -> row == notification }, status = "人工许可意见已提交到会话上下文，后续回合再决定执行") }
        } finally { tracking.finish(ticket, confirmed) }
    }
    fun startReview(type: String, value: String, title: String) {
        try {
            val target = OriginalSessionActions.reviewTarget(type, value, title)
            val thread = requireNotNull(state.value.threadId) { "请先选择会话" }
            reserve(OriginalTurnTask.Review(thread, target), "代码审查")
        } catch (failure: Exception) { error(failure) }
    }
    private fun reserve(task: OriginalTurnTask, label: String) {
        if(queue.operations.state.value.busy && task !is OriginalTurnTask.QueueStart){error(IllegalStateException("队列正在读取或确认，请稍后开始其他任务"));return}
        if (state.value.loading || state.value.working || configurationChanging || !state.value.connected) return
        if (!selectedThreadUsable()) { error(IllegalStateException("会话状态已变化，请先重新打开或恢复会话")); return }
        if (state.value.turnId != null && task !is OriginalTurnTask.QueueAdd) { error(IllegalStateException("已恢复的回合仍在运行，请先停止或追加指令")); return }
        if (otherTaskRunning()) { error(IllegalStateException("另一个任务正在运行")); return }
        reservedTask = task
        mutable.update { it.copy(working = true, error = null) }
        try { startTask(label) } catch (failure: Exception) { reservedTask = null; mutable.update { it.copy(working = false) }; error(failure) }
    }
    fun canAddQueue()=!queue.operations.state.value.busy && state.value.connected && state.value.threadId!=null && selectedThreadUsable() && !state.value.loading && !configurationChanging && (!state.value.working || state.value.turnId!=null || state.value.queueMonitoring)
    fun addQueued(text:String){
        if(!canAddQueue() || text.isBlank())return
        if(state.value.working)queue.add(text)
        else reserve(OriginalTurnTask.QueueAdd(requireNotNull(state.value.threadId),text,UUID.randomUUID().toString()),"加入并托管待办队列")
    }
    fun startQueued(approved:JsonObject){
        val thread=state.value.threadId ?: return
        queue.prepareStart(approved){if(state.value.threadId==thread)reserve(OriginalTurnTask.QueueStart(thread,approved.string("id")),"执行待办队列")}
    }
    private suspend fun awaitQueueDrained(api:OriginalCodexClient,thread:String,epoch:Long,required:Boolean){
        mutable.update{it.copy(queueMonitoring=true)}
        var stableIdle=false
        try{
            while(current(epoch,api.connection)){
                val version=state.value.turnNotificationVersion;val revision=queue.state.value.revision
                val rows=try{queue.all(api,thread)}catch(failure:RpcException){
                    if(!required && (failure.code == -32601 || failure.message.orEmpty().contains("user message queue is unavailable")))return
                    throw failure
                }
                val snapshot=api.read(thread).jsonObject.getValue("thread").jsonObject
                ensureCurrent(epoch,api.connection)
                if(version!=state.value.turnNotificationVersion || revision!=queue.state.value.revision || queue.operations.state.value.mutating){stableIdle=false;delay(100);continue}
                val turns=(snapshot["turns"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}
                val active=turns.lastOrNull{it.string("status")=="inProgress"}?.string("id")
                val status=(snapshot["status"] as? JsonObject)?.string("type").orEmpty()
                if(status !in setOf("idle","active"))throw ThreadUnavailable("会话已卸载或状态未知，请重新打开核查队列")
                lifecycle.accept(epoch,OriginalLifecycleEvent.QueueSnapshot(thread,active,turns.filter{it.string("status")!="inProgress"}.map{it.string("id")}));syncLifecycle()
                val idle=status=="idle" && active==null
                val interrupted=turns.lastOrNull()?.string("status")=="interrupted"
                if(idle && (rows.isEmpty() || interrupted)){
                    if(stableIdle){
                        if(rows.isNotEmpty())mutable.update{it.copy(status="当前任务已停止，剩余待办保留，可稍后执行")}
                        else mutable.update{it.copy(status=if(it.error!=null)it.status else "任务与待办队列已处理结束")}
                        return
                    }
                    stableIdle=true
                }else stableIdle=false
                // 事件唤醒优先；一秒兜底读取实际运行状态，避免依赖通知数量或ACK推断完成。
                withTimeoutOrNull(if(stableIdle)200L else 1000L){state.first{it.turnNotificationVersion!=version || queue.state.value.revision!=revision || !it.connected}}
            }
        }catch(failure:Exception){if(failure !is CancellationException && current(epoch,api.connection))api.connection.close();throw failure}
        finally{if(current(epoch,api.connection))mutable.update{it.copy(queueMonitoring=false)}}
    }
    @Suppress("UNUSED_PARAMETER")
    suspend fun runReserved(text: String) {
        if (!state.value.working) return
        val task = reservedTask ?: return
        reservedTask = null
        val epoch = lifecycle.state.connectionEpoch
        val api = client ?: run { mutable.update { it.copy(working = false) }; return }
        var waiter: TurnWaiter? = null
        var turnFinished = false
        try {
            if (state.value.threadId == null) {
                val thread = api.start(workspace.absolutePath, state.value.model).jsonObject.getValue("thread").jsonObject
                ensureCurrent(epoch, api.connection); selectThread(thread)
            }
            val thread = requireNotNull(lifecycle.state.threadId)
            if(task is OriginalTurnTask.QueueAdd){
                check(task.threadId==thread)
                val result=api.addQueue(thread,OriginalCodexClient.textInput(task.text),task.clientMessageId).jsonObject["queuedSubmission"] as? JsonObject ?: error("加入结果缺少待办条目")
                require(result.string("id").isNotBlank() && result.string("clientUserMessageId")==task.clientMessageId){"加入结果标识不匹配，请刷新核查"}
                awaitQueueDrained(api,thread,epoch,true);turnFinished=true;return
            }
            val request = UUID.randomUUID().toString()
            lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested(request))
            val waiting = TurnWaiter(epoch, thread, request).also { waiter = it; turnWaiter = it }
            val response = coroutineScope {
                val requestResponse = async {
                    when (task) {
                        is OriginalTurnTask.QueueAdd -> error("加入队列由独立托管分支处理")
                        is OriginalTurnTask.QueueStart -> {check(thread==task.threadId);api.startQueue(thread,task.id)}
                        is OriginalTurnTask.Prompt -> api.turn(thread, task.text, configLoader().reasoning)
                        is OriginalTurnTask.Review -> { check(thread == task.threadId) { "审查所属会话已变化" }; api.review(thread, task.target) }
                        is OriginalTurnTask.Shell -> { check(thread == task.threadId); api.threadShell(thread, task.command, task.timeoutMs) }
                    }
                }
                select<JsonElement> {
                    requestResponse.onAwait { it }
                    waiting.invalidated.onAwait { it }
                }
            }.jsonObject
            if (task is OriginalTurnTask.Review) require(response.string("reviewThreadId") == thread) { "核心返回的审查会话与当前会话不同" }
            ensureCurrent(epoch, api.connection)
            if (state.value.threadUpdates.threads[thread]?.availability in setOf("closed", "deleted")) throw ThreadUnavailable("会话已关闭、删除或变更，请重新打开后核对任务结果")
            val turnId = if (task is OriginalTurnTask.Shell) withTimeout(60_000) { waiting.observedStart.await() } else (response["turn"] as? JsonObject)?.string("id").orEmpty()
            require(turnId.isNotBlank()) { "原版任务启动响应缺少回合标识，不能确认任务状态" }
            waiting.turnId = turnId
            lifecycle.accept(epoch, OriginalLifecycleEvent.StartResponded(request, thread, turnId)); syncLifecycle()
            waiting.outcomes[turnId]?.let { outcome -> applyOutcome(outcome); waiting.finished.complete(Unit) }
            if (!waiting.finished.isCompleted) mutable.update { it.copy(status = "正在处理") }
            waiting.finished.await(); turnFinished = true
            awaitQueueDrained(api,thread,epoch,task is OriginalTurnTask.QueueStart)
            if (current(epoch, api.connection)) refreshThreads(api, false)
        } catch (cancelled: CancellationException) {
            if (current(epoch, api.connection)) {
                if (cancelled is TimeoutCancellationException) { close(); error(IllegalStateException("任务请求超时，已关闭核心。请恢复会话检查修改；不会自动重发任务。")) }
                else stop()
            }
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (failure: Exception) {
            if (current(epoch, api.connection)) {
                if(failure is RpcException && (task is OriginalTurnTask.QueueAdd || task is OriginalTurnTask.QueueStart) && failure.code in setOf(-32601,-32602)){
                    turnWaiter?.let{lifecycle.accept(epoch,OriginalLifecycleEvent.StartRejected(it.requestId))};syncLifecycle()
                    error(OriginalQueueData.label(failure))
                } else if (failure is ThreadUnavailable) error(failure)
                else if (turnFinished) error(IllegalStateException("任务已结束，但刷新会话失败：${failure.message.orEmpty()}"))
                else { close(); error(IllegalStateException("任务状态无法确认，核心已关闭。请恢复会话检查修改。${failure.message.orEmpty()}")) }
            }
        } finally {
            if (turnWaiter === waiter) turnWaiter = null
            if (current(epoch, api.connection)) mutable.update { it.copy(working = false) }
        }
    }
    private fun applyOutcome(outcome: Outcome) {
        mutable.update { it.copy(status = when (outcome.status) { "failed" -> "任务失败"; "interrupted" -> "任务已停止"; else -> "任务完成" }, error = outcome.error) }
    }
    fun steer(text: String) = operation { api -> api.steer(requireNotNull(state.value.threadId), requireNotNull(state.value.turnId), text) }
    fun stop() {
        if (stopping?.isActive == true) return
        val epoch = lifecycle.state.connectionEpoch
        val api = client ?: return
        stopping = scope.launch {
            val snapshot = state.value
            val rpc = api.connection
            try {
                if (snapshot.threadId != null && snapshot.turnId != null) {
                    lifecycle.accept(epoch, OriginalLifecycleEvent.StopRequested(snapshot.threadId, snapshot.turnId)); syncLifecycle()
                    mutable.update { it.copy(status = "正在停止任务") }
                    withTimeout(10_000) {
                        api.interrupt(snapshot.threadId, snapshot.turnId)
                        ensureCurrent(epoch, rpc)
                        lifecycle.accept(epoch, OriginalLifecycleEvent.InterruptAccepted(snapshot.threadId, snapshot.turnId)); syncLifecycle()
                        state.first { it.turnId != snapshot.turnId || !it.connected }
                    }
                } else if (snapshot.working && current(epoch, rpc)) { close(); return@launch }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (current(epoch, rpc) && state.value.turnId == snapshot.turnId) { close(); error(IllegalStateException("核心未及时响应停止，已结束本机核心进程；已完成的修改仍保留")) }
            } catch (failure: Exception) {
                if (current(epoch, rpc)) { if (state.value.turnId == snapshot.turnId) close(); error(failure) }
            }
        }
    }
    fun answer(request: ServerMessage.Request, result: JsonElement) = scope.launch {
        val epoch = lifecycle.state.connectionEpoch
        val rpc = connection ?: return@launch
        // 请求 ID 可在重连后复用，必须核对当前队列中的同一个请求实例。
        if (state.value.requests.none { it === request }) return@launch
        try {
            rpc.respond(request.id, result)
            if (current(epoch, rpc)) mutable.update { it.copy(requests = it.requests.filterNot { pending -> pending === request }) }
        } catch (failure: Exception) { if (current(epoch, rpc)) error(failure) }
    }
    fun dismissRuntimeNotice(key: String) { remainingEvents.dismiss(key);mutable.update { it.copy(events = remainingEvents.state) } }
    fun executeAndroidTool(request: ServerMessage.Request) {
        if(request.method != "item/tool/call" || state.value.requests.none { it === request } || request in toolJobs)return
        val rpc = connection ?: return;val epoch = lifecycle.state.connectionEpoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(toolBusy = true) }
            try {
                val params = request.params as? JsonObject ?: buildJsonObject {}
                val result = withContext(Dispatchers.IO) { androidTools.execute(params) }
                if(current(epoch,rpc) && state.value.requests.any { it === request }) {
                    rpc.respond(request.id,result)
                    mutable.update { it.copy(requests = it.requests.filterNot { pending -> pending === request }) }
                    remainingEvents.notice("tool/${request.id}","手机工具执行结果",result.toString(),params.string("threadId"),params.string("turnId"))
                    mutable.update { it.copy(events = remainingEvents.state) }
                }
            } catch(cancelled: CancellationException) { throw cancelled } catch(failure: Exception) { if(current(epoch,rpc)) error(failure) } finally { toolJobs.remove(request);if(current(epoch,rpc))mutable.update { it.copy(toolBusy = toolJobs.isNotEmpty()) } }
        }
        toolJobs[request] = job;job.start()
    }
    private suspend fun onMessage(epoch: Long, rpc: AppServerConnection, message: ServerMessage) {
        if (!current(epoch, rpc)) return
        when (message) {
            is ServerMessage.Request -> {
                if (message.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request", "item/permissions/requestApproval", "execCommandApproval", "applyPatchApproval", "item/tool/call")) {
                    if (state.value.requests.size >= 32) rpc.reject(message.id, -32000, "待处理交互数量达到上限")
                    else mutable.update { it.copy(requests = it.requests + message) }
                } else if(message.method == "currentTime/read") {
                    rpc.respond(message.id,buildJsonObject{put("currentTimeAt",System.currentTimeMillis()/1000L)})
                } else if(message.method == "account/chatgptAuthTokens/refresh" || message.method == "attestation/generate") {
                    val reason = if(message.method == "attestation/generate") "当前Android包未接入受服务端认可的设备证明提供者；本连接未声明requestAttestation能力" else "当前使用原版核心管理的登录，未配置外部账号令牌刷新提供者；请在授权页重新登录"
                    rpc.reject(message.id,-32001,reason)
                    remainingEvents.notice(message.method,"外部认证能力暂不可用",reason)
                    mutable.update { it.copy(events = remainingEvents.state) }
                } else rpc.reject(message.id)
            }
            is ServerMessage.Notification -> notification(epoch, rpc, message.method, message.params as? JsonObject ?: return)
        }
    }
    private suspend fun notification(epoch: Long, rpc: AppServerConnection, method: String, params: JsonObject) {
        if (!current(epoch, rpc)) return
        if(method=="project/changed")projects.changed()
        if(method=="remoteControl/status/changed")connections.remoteChanged(params)
        if(method=="mcpServer/event/stream/notification")connections.streamEvent(params)
        if (method in OriginalRemainingEvents.methods) {
            try {
                if(method=="process/outputDelta")commands.processOutput(params)
                if(method=="process/exited")commands.processExited(params)
                if(method.startsWith("fuzzyFileSearch/session"))search.notification(method,params)
                if(method.startsWith("thread/realtime/"))realtime.notification(method,params)
                if(method=="thread/realtime/started" && !realtime.acceptsStarted(params.string("threadId")))return
                remainingEvents.accept(method,params)
                if(method == "thread/realtime/outputAudio/delta" && params.string("threadId") == state.value.threadId && remainingEvents.state.realtime[state.value.threadId]?.let { it.active && !it.sdpReceived } == true)
                    (params["audio"] as? JsonObject)?.let(realtimeAudio::offer)
                if(method in setOf("thread/realtime/closed","thread/realtime/started","thread/realtime/sdp","thread/realtime/error") && params.string("threadId") == state.value.threadId) {microphone.stop();realtimeAudio.stop()}
                mutable.update { it.copy(events = remainingEvents.state) }
            } catch(failure: Exception) {
                remainingEvents.notice("decode/$method","收到无法解析的运行信息", "$method：${failure.message.orEmpty().take(200)}")
                mutable.update { it.copy(events = remainingEvents.state) }
            }
            return
        }
        if(method=="thread/queue/changed")queue.changed(params.string("threadId"))
        if (method in setOf("thread/started", "thread/readState/changed", "thread/archived", "thread/deleted", "thread/unarchived", "thread/closed", "thread/reverted", "thread/name/updated", "thread/attachment/updated", "thread/prediction/updated", "thread/queue/changed", "project/changed", "thread/project/updated", "thread/environment/connected", "thread/environment/disconnected", "thread/settings/updated")) {
            val updated = OriginalThreadUpdates.reduce(state.value.threadUpdates, method, params)
            val id = if (method == "thread/started") (params["thread"] as? JsonObject)?.string("id").orEmpty() else params.string("threadId")
            val started = updated.started
            mutable.update { old ->
                val rows = if (method == "thread/started" && !old.archived && started != null)
                    (listOf(started) + old.threads).distinctBy { it.string("id") } else old.threads
                old.copy(threadUpdates = updated, threads = OriginalThreadUpdates.overlay(rows, updated, old.archived, old.threadListVersion),
                    model = if (id == old.threadId && method == "thread/settings/updated") updated.threads[id]?.settings?.string("model", old.model) ?: old.model else old.model,
                    loadedThreads = if (method in setOf("thread/closed", "thread/deleted")) old.loadedThreads.copy(entries = old.loadedThreads.entries.filterNot { it == id }) else old.loadedThreads)
            }
            if (id == state.value.threadId && method in setOf("thread/closed", "thread/deleted")) {
                // 原版仅在该线程运行实例被卸载/删除后发终态；保留已显示正文，不把后台线程终态当当前回合完成。
                realtime.notification("thread/realtime/closed",buildJsonObject{put("threadId",id);put("reason","会话已卸载或删除")});microphone.stop();realtimeAudio.stop()
                val failure = ThreadUnavailable(if (method == "thread/deleted") "会话已删除，已停止等待该会话的任务" else "会话已卸载，请重新打开后继续")
                turnWaiter?.takeIf { it.threadId == id }?.let { it.invalidated.completeExceptionally(failure); it.observedStart.completeExceptionally(failure); it.finished.completeExceptionally(failure) }
                reservedTask = null
                lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadUnavailable(id)); syncLifecycle()
                mutable.update { it.copy(working = if (turnWaiter == null) false else it.working, status = failure.message.orEmpty(), requests = it.requests.filterNot { request ->
                    val p = request.params as? JsonObject
                    p?.string("threadId") == id || p?.string("conversationId") == id
                }) }
            }
            return
        }
        if (method == "command/exec/outputDelta") { commands.output(params); return }
        if (method == "thread/goal/updated") {
            if (params.string("threadId") == state.value.threadId) mutable.update { it.copy(goal = params["goal"] as? JsonObject) }
            return
        }
        if (method == "thread/goal/cleared") {
            if (params.string("threadId") == state.value.threadId) mutable.update { it.copy(goal = null) }
            return
        }
        if (method == "account/gatewayOAuth/changed") { services.gatewayChanged(params); return }
        if (method == "externalAgentConfig/import/progress") { migration.importProgress(params, false); return }
        if (method == "externalAgentConfig/import/completed") { migration.importProgress(params, true); return }
        if (method == "item/autoApprovalReview/completed") {
            mutable.update { it.copy(guardianReviews = (it.guardianReviews.filterNot { row -> row.string("reviewId") == params.string("reviewId") && row.string("threadId") == params.string("threadId") } + params).takeLast(64)) }; return
        }
        if (method == "skills/changed") { extensions.skillsChanged(); return }
        if (method == "thread/status/changed") {
            val id = params.string("threadId"); val status = params["status"] as? JsonObject ?: return
            if (id.isNotBlank()) mutable.update { previous -> previous.copy(threadStatuses = ((previous.threadStatuses - id) + (id to status)).entries.toList().takeLast(200).associate { it.toPair() }) }
            return
        }
        if (method == "fs/changed") { files.changed(params); return }
        if (method == "mcpServer/startupStatus/updated") {
            mcp.runtimeChanged(params.string("threadId").ifBlank { null }, params.string("name"), params.string("status"), params.string("error").ifBlank { null })
            return
        }
        if (method == "hook/started" || method == "hook/completed") {
            (params["run"] as? JsonObject)?.let { run -> extensions.recordHook(JsonObject(run + mapOf("threadId" to (params["threadId"] ?: JsonNull)))) }
            return
        }
        if (method == "account/login/completed") {
            if (state.value.loginId != null && params.string("loginId") != state.value.loginId) return
            val success = params["success"]?.jsonPrimitive?.booleanOrNull == true
            mutable.update { it.copy(loginId = null, loginUrl = null, loginCode = null, error = if (success) null else params.string("error", "登录未完成")) }
            // 不在读取循环里等待另一条 RPC，否则响应无人读取而死锁。
            if (success) scope.launch { runSuspendCatching { client?.let { readAccount(it) } }.onFailure { if (current(epoch, rpc)) error(it) } }
            return
        }
        if (method == "mcpServer/oauthLogin/completed") {
            val snapshot = state.value
            if (params.string("name") != snapshot.mcpAuthorizationName || snapshot.mcpLoginId != null && params.string("loginId") != snapshot.mcpLoginId) return
            val success = params["success"]?.jsonPrimitive?.booleanOrNull == true
            mutable.update { it.copy(mcpAuthorizationUrl = null, mcpLoginId = null, mcpAuthorizationName = null, status = if (success) "MCP 授权完成" else "MCP 授权未完成", error = if (success) null else params.string("error", "授权失败")) }
            return
        }
        if (method == "account/updated") {
            scope.launch { runSuspendCatching { client?.let { readAccount(it) } }.onFailure { if (current(epoch, rpc)) error(it) } }; return
        }
        if (method == "serverRequest/resolved") { toolJobs.keys.filter { it.id == params["requestId"] }.toList().forEach { toolJobs.remove(it)?.cancel() }; mutable.update { it.copy(requests = it.requests.filterNot { r -> r.id == params["requestId"] }) }; return }
        if (params.string("threadId").isNotBlank() && params.string("threadId") != state.value.threadId) return
        if (state.value.threadUpdates.threads[state.value.threadId]?.availability in setOf("closed", "deleted") && (method.startsWith("turn/") || method.startsWith("item/"))) return
        when (method) {
            "turn/plan/updated", "thread/tokenUsage/updated", "item/mcpToolCall/progress", "item/commandExecution/terminalInteraction", "item/fileChange/outputDelta" -> {
                // 非当前会话/未知会话的通知不得进入正在查看的对话。
                if (params.string("threadId") != state.value.threadId) return
                mutable.update { it.copy(turnProgress = OriginalLiveUpdates.reduce(it.turnProgress, method, params)) }
            }
            "turn/started" -> {
                mutable.update{it.copy(turnNotificationVersion=it.turnNotificationVersion+1)}
                val id = (params["turn"] as? JsonObject)?.string("id").orEmpty()
                if (id.isNotBlank() && id !in lifecycle.state.completedTurnIds) turnWaiter?.takeIf { it.epoch == epoch && it.threadId == params.string("threadId") }?.observedStart?.complete(id)
                lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted(params.string("threadId"), id)); syncLifecycle()
                if (lifecycle.state.turnId == id && id.isNotBlank()) mutable.update { it.copy(status = "正在处理") }
            }
            "turn/completed" -> {
                mutable.update{it.copy(turnNotificationVersion=it.turnNotificationVersion+1)}
                val turn = params["turn"] as? JsonObject ?: return
                val id = turn.string("id"); if (id.isBlank()) return
                val wasActive = lifecycle.state.turnId == id
                lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted(params.string("threadId"), id)); syncLifecycle()
                val outcome = Outcome(turn.string("status"), (turn["error"] as? JsonObject)?.string("message")?.takeIf { it.isNotBlank() }?.takeLast(4096))
                val waiting = turnWaiter?.takeIf { it.epoch == epoch && it.threadId == params.string("threadId") }
                if (waiting != null) {
                    waiting.outcomes[id] = outcome
                    while (waiting.outcomes.size > 128) waiting.outcomes.remove(waiting.outcomes.keys.first())
                }
                if (wasActive || waiting?.turnId == id) applyOutcome(outcome)
                mutable.update { it.copy(requests = it.requests.filterNot { r -> (r.params as? JsonObject)?.string("turnId") == id }) }
                if (waiting?.turnId == id) waiting.finished.complete(Unit)
            }
            "item/fileChange/patchUpdated" -> mutable.update { it.copy(items = OriginalThreadView.patch(it.items, params.string("itemId"), params["changes"] as? JsonArray ?: buildJsonArray {})) }
            "turn/diff/updated" -> if (params.string("turnId") == lifecycle.state.turnId) mutable.update { it.copy(report = params.string("diff")) }
            "thread/compacted" -> mutable.update { it.copy(status = "上下文已压缩") }
            "item/started", "item/completed" -> (params["item"] as? JsonObject)?.let { raw ->
                val item = OriginalThreadView.item(raw)
                mutable.update { it.copy(items = OriginalThreadView.upsert(it.items, item)) }
            }
            "item/agentMessage/delta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta", "item/commandExecution/outputDelta" -> {
                val type = when (method) { "item/agentMessage/delta" -> "agentMessage"; "item/plan/delta" -> "plan"; "item/commandExecution/outputDelta" -> "commandExecution"; else -> "reasoning" }
                mutable.update { it.copy(items = OriginalThreadView.delta(it.items, params.string("itemId"), type, params.string("delta"))) }
            }
            "error" -> mutable.update { it.copy(error = (params["error"] as? JsonObject)?.string("message", "任务发生错误") ?: "任务发生错误") }
            "warning" -> mutable.update { it.copy(status = params.string("message", "核心有一条提醒")) }
        }
    }
}
