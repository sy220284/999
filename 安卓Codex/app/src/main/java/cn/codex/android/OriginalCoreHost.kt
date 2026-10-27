package cn.codex.android

import android.content.Context
import android.content.Intent
import cn.codex.core.*
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private data class Outcome(val status: String, val error: String?)
    private data class TurnWaiter(val epoch: Long, val threadId: String, val requestId: String,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(), var turnId: String? = null,
        val outcomes: LinkedHashMap<String, Outcome> = linkedMapOf())
    private var turnWaiter: TurnWaiter? = null
    private var reservedTask: OriginalTurnTask? = null
    private var stopping: Job? = null
    private val workspace = File(context.filesDir, "工作区/原版项目").apply { mkdirs() }
    private val engineHome = File(context.filesDir, "原版核心数据").apply { mkdirs() }
    val configurationJournal get() = (context.applicationContext as CodexApplication).configurationJournal
    val extensions: OriginalExtensionsController = OriginalExtensionsController(scope, workspace.absolutePath, configurationJournal.lifecycle("插件、技能与自动规则")) { state.value.working || state.value.turnId != null || state.value.loading || otherTaskRunning() || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || commands.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running }
    val mcp: OriginalMcpController = OriginalMcpController(scope, workspace.absolutePath,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || extensions.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || commands.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running },
        threadId = { state.value.threadId }, mutationLifecycle = configurationJournal.lifecycle("MCP 服务操作"))
    val commands: OriginalCommandController = OriginalCommandController(scope,
        busyElsewhere = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || extensions.state.value.mutating || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating || (context.applicationContext as CodexApplication).fullTerminal.state.value.running },
        lifecycle = configurationJournal.lifecycle("原版独立命令"))
    val settings: OriginalSettingsController = OriginalSettingsController(scope, workspace.absolutePath,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging },
        threadId = { state.value.threadId }, lifecycle = configurationJournal.lifecycle("原版实验设置"))
    val files: OriginalFilesController = OriginalFilesController(scope, workspace,
        activeTask = { state.value.working || state.value.turnId != null || state.value.loading || state.value.closing || otherTaskRunning() || configurationChanging },
        lifecycle = configurationJournal.lifecycle("原版项目文件"))
    fun commandWorkspace() = workspace.absolutePath
    val configurationChanging get() = (context.applicationContext as CodexApplication).gitOperations.state.value.running || (context.applicationContext as CodexApplication).fullTerminal.state.value.running || commands.state.value.running || extensions.state.value.mutating || mcp.state.value.mutating || settings.state.value.mutating || files.state.value.mutating
    fun mcpImports() = McpTools(context).connections().map { it.name to it.url }
    private fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: "原版核心操作失败") } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun operation(block: suspend (OriginalCodexClient) -> Unit): Job? {
        if (state.value.loading || state.value.closing || configurationChanging) return null
        val api = client ?: run { error(IllegalStateException("请先启动原版核心")); return null }
        mutable.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try { block(api) }
            catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) { if (client === api) error(IllegalStateException("原版请求超时，请刷新实际状态；不会自动重放操作")) }
                else throw cancelled
            }
            catch (failure: Exception) { if (client === api) error(failure) }
            finally { if (client === api) mutable.update { it.copy(loading = false) } }
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
            try { withTimeout(startupTimeoutMs) { startup.withLock { closingJob?.join(); startProcess(epoch) } } }
            catch (cancelled: CancellationException) {
                if (lifecycle.isCurrent(epoch)) {
                    closeSession(cancelStartup = false)
                    if (cancelled is TimeoutCancellationException) error(IllegalStateException("原版核心启动超时，已关闭启动中的进程，请核查后重试"))
                }
                if (cancelled !is TimeoutCancellationException) throw cancelled
            }
            catch (failure: Exception) { if (lifecycle.isCurrent(epoch)) { closeSession(cancelStartup = false); error(failure) } }
            finally { if (lifecycle.isCurrent(epoch)) mutable.update { it.copy(loading = false) } }
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
            try { rpc.messages.collect { message -> if (current(epoch, rpc)) onMessage(epoch, rpc, message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current(epoch, rpc)) { close(); error(IllegalStateException("原版核心连接已中断，请重新启动后恢复会话")) } }
        }
        val api = OriginalCodexClient(rpc)
        api.initialize()
        ensureCurrent(epoch, rpc)
        if (config.apiKey.isNotBlank() && config.endpoint.trimEnd('/') == "https://api.openai.com/v1" && api.account().jsonObject["account"] == JsonNull) api.loginApiKey(config.apiKey)
        ensureCurrent(epoch, rpc)
        client = api; extensions.bind(api); mcp.bind(api); commands.bind(api); settings.bind(api); files.bind(api)
        mutable.update { it.copy(connected = true, status = "原版核心已连接 · 凭证由手机 Keystore 加密保存", model = config.model) }
        refreshThreads(api, false); readAccount(api, epoch)
    }
    fun close() { closeSession(cancelStartup = true) }
    private fun closeSession(cancelStartup: Boolean) {
        if (state.value.closing) return
        lifecycle.disconnect(lifecycle.state.connectionEpoch)
        if (cancelStartup) startupJob?.cancel()
        operationJob?.cancel(); operationJob = null
        reservedTask = null
        stopping?.cancel(); stopping = null
        extensions.bind(null); mcp.bind(null); commands.bind(null); settings.bind(null); files.bind(null)
        turnWaiter?.finished?.completeExceptionally(IllegalStateException("原版核心已停止")); turnWaiter = null
        listener?.cancel(); listener = null
        val session = engine
        engine = null; client = null
        mutable.update { OriginalState(connectionEpoch = lifecycle.state.connectionEpoch, model = it.model, closing = true, status = "正在关闭核心") }
        closingJob = scope.launch(Dispatchers.IO) {
            try { session?.close() }
            catch (_: Exception) { mutable.update { it.copy(error = "核心资源回收未能确认，请检查进程状态后再启动") } }
            finally { mutable.update { it.copy(closing = false, status = "原版核心已关闭") } }
        }
    }
    fun refresh(archived: Boolean = state.value.archived, more: Boolean = false) = operation { api -> refreshThreads(api, archived, more) }
    private suspend fun refreshThreads(api: OriginalCodexClient, archived: Boolean, more: Boolean = false) {
        val cursor = if (more) state.value.nextCursor ?: return else null
        val response = api.threads(cursor, archived).jsonObject
        val entries = (response["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        mutable.update { it.copy(threads = (if (more) it.threads + entries else entries).distinctBy { row -> row.string("id") }, nextCursor = response.string("nextCursor").ifBlank { null }, archived = archived) }
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
        val response = api.resume(id).jsonObject
        selectThread(response.getValue("thread").jsonObject)
        mutable.update { it.copy(historyCursor = response.string("itemsBackwardsCursor").ifBlank { null }) }
    }
    private fun selectThread(thread: JsonObject) {
        val turns = (thread["turns"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val active = turns.lastOrNull { it.string("status") == "inProgress" }?.string("id")
        val items = OriginalThreadView.history(thread)
        lifecycle.accept(lifecycle.state.connectionEpoch, OriginalLifecycleEvent.ThreadSelected(thread.string("id"), active))
        syncLifecycle()
        mutable.update { it.copy(items = items, threadGitInfo = thread["gitInfo"] as? JsonObject, attachmentOwners = OriginalSessionPage(), ownerType = "", ownerKey = "", goal = null, attachments = emptyList(), attachmentCursor = null, turns = emptyList(), turnCursor = null, historyCursor = null, model = thread.string("model", it.model), status = if (lifecycle.state.turnId == null) "会话已恢复" else "已恢复正在运行的回合") }
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
        val thread = requireNotNull(state.value.threadId); val cursor = if (more) state.value.attachmentCursor ?: return@operation else null
        val page = api.attachments(thread, cursor).jsonObject
        if (state.value.threadId == thread) mutable.update { it.copy(attachments = ((if (more) it.attachments else emptyList()) + objects(page)).distinctBy { row -> row.string("id") }, attachmentCursor = page.string("nextCursor").ifBlank { null }) }
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
        lifecycle.accept(lifecycle.state.connectionEpoch, OriginalLifecycleEvent.ThreadCleared); syncLifecycle()
        mutable.update { it.copy(items = emptyList(), goal = null, attachments = emptyList(), turns = emptyList(), historyCursor = null, attachmentCursor = null, turnCursor = null, status = if (delete) "会话已删除" else "已关闭当前会话视图") }
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
    fun startReview(type: String, value: String, title: String) {
        try {
            val target = OriginalSessionActions.reviewTarget(type, value, title)
            val thread = requireNotNull(state.value.threadId) { "请先选择会话" }
            reserve(OriginalTurnTask.Review(thread, target), "代码审查")
        } catch (failure: Exception) { error(failure) }
    }
    private fun reserve(task: OriginalTurnTask, label: String) {
        if (state.value.loading || state.value.working || configurationChanging || !state.value.connected) return
        if (state.value.turnId != null) { error(IllegalStateException("已恢复的回合仍在运行，请先停止或追加指令")); return }
        if (otherTaskRunning()) { error(IllegalStateException("另一个任务正在运行")); return }
        reservedTask = task
        mutable.update { it.copy(working = true, error = null) }
        try { startTask(label) }
        catch (failure: Exception) { reservedTask = null; mutable.update { it.copy(working = false) }; error(failure) }
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
            val request = UUID.randomUUID().toString()
            lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested(request))
            val waiting = TurnWaiter(epoch, thread, request).also { waiter = it; turnWaiter = it }
            val response = when (task) {
                is OriginalTurnTask.Prompt -> api.turn(thread, task.text, configLoader().reasoning)
                is OriginalTurnTask.Review -> { check(thread == task.threadId) { "审查所属会话已变化" }; api.review(thread, task.target) }
            }.jsonObject
            if (task is OriginalTurnTask.Review) require(response.string("reviewThreadId") == thread) { "核心返回的审查会话与当前会话不同" }
            ensureCurrent(epoch, api.connection)
            val turnId = (response["turn"] as? JsonObject)?.string("id").orEmpty()
            require(turnId.isNotBlank()) { "原版任务启动响应缺少回合标识，不能确认任务状态" }
            waiting.turnId = turnId
            lifecycle.accept(epoch, OriginalLifecycleEvent.StartResponded(request, thread, turnId)); syncLifecycle()
            waiting.outcomes[turnId]?.let { outcome -> applyOutcome(outcome); waiting.finished.complete(Unit) }
            if (!waiting.finished.isCompleted) mutable.update { it.copy(status = "正在处理") }
            waiting.finished.await(); turnFinished = true
            if (current(epoch, api.connection)) refreshThreads(api, false)
        } catch (cancelled: CancellationException) {
            if (current(epoch, api.connection)) {
                if (cancelled is TimeoutCancellationException) { close(); error(IllegalStateException("任务请求超时，已关闭核心。请恢复会话检查修改；不会自动重发任务。")) }
                else stop()
            }
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (failure: Exception) {
            if (current(epoch, api.connection)) {
                if (turnFinished) error(IllegalStateException("任务已结束，但刷新会话失败：${failure.message.orEmpty()}"))
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
    private suspend fun onMessage(epoch: Long, rpc: AppServerConnection, message: ServerMessage) {
        if (!current(epoch, rpc)) return
        when (message) {
            is ServerMessage.Request -> {
                if (message.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request", "item/permissions/requestApproval")) {
                    if (state.value.requests.size >= 32) rpc.reject(message.id, -32000, "待处理交互数量达到上限")
                    else mutable.update { it.copy(requests = it.requests + message) }
                } else rpc.reject(message.id)
            }
            is ServerMessage.Notification -> notification(epoch, rpc, message.method, message.params as? JsonObject ?: return)
        }
    }
    private suspend fun notification(epoch: Long, rpc: AppServerConnection, method: String, params: JsonObject) {
        if (!current(epoch, rpc)) return
        if (method == "command/exec/outputDelta") { commands.output(params); return }
        if (method == "thread/goal/updated") {
            if (params.string("threadId") == state.value.threadId) mutable.update { it.copy(goal = params["goal"] as? JsonObject) }
            return
        }
        if (method == "thread/goal/cleared") {
            if (params.string("threadId") == state.value.threadId) mutable.update { it.copy(goal = null) }
            return
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
        if (method == "serverRequest/resolved") { mutable.update { it.copy(requests = it.requests.filterNot { r -> r.id == params["requestId"] }) }; return }
        if (params.string("threadId").isNotBlank() && params.string("threadId") != state.value.threadId) return
        when (method) {
            "turn/started" -> {
                val id = (params["turn"] as? JsonObject)?.string("id").orEmpty()
                lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted(params.string("threadId"), id)); syncLifecycle()
                if (lifecycle.state.turnId == id && id.isNotBlank()) mutable.update { it.copy(status = "正在处理") }
            }
            "turn/completed" -> {
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
