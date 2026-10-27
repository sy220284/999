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
import java.net.URI

/** Application 持有连接；Compose 只订阅状态。原版任务由独立前台服务等待完成。 */
data class OriginalState(
    val connected: Boolean = false, val closing: Boolean = false, val loading: Boolean = false, val working: Boolean = false,
    val threadId: String? = null, val turnId: String? = null,
    val threads: List<JsonObject> = emptyList(), val nextCursor: String? = null, val archived: Boolean = false,
    val items: List<OriginalItem> = emptyList(), val requests: List<ServerMessage.Request> = emptyList(),
    val account: String = "尚未读取账号", val error: String? = null, val status: String = "未启动原版核心",
    val loginUrl: String? = null, val loginCode: String? = null, val loginId: String? = null,
    val historyCursor: String? = null,
    val mcpAuthorizationUrl: String? = null, val mcpAuthorizationName: String? = null, val mcpLoginId: String? = null,
    val models: List<String> = emptyList(), val model: String = "", val report: String = ""
)
class OriginalCoreHost(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(OriginalState())
    val state = mutable.asStateFlow()
    private val startup = Mutex()
    private var process: NativeCoreProcess? = null
    private var vaultServer: OriginalVaultServer? = null
    private var connection: AppServerConnection? = null
    private var client: OriginalCodexClient? = null
    private var listener: Job? = null
    private var closingJob: Job? = null
    private var completion: CompletableDeferred<Unit>? = null
    private var stopping: Job? = null
    private val workspace = File(context.filesDir, "工作区/原版项目").apply { mkdirs() }
    private val engineHome = File(context.filesDir, "原版核心数据").apply { mkdirs() }
    private fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: "原版核心操作失败") } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun operation(block: suspend (OriginalCodexClient) -> Unit) = scope.launch {
        if (state.value.loading) return@launch
        mutable.update { it.copy(loading = true, error = null) }
        try { block(checkNotNull(client) { "请先启动原版核心" }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure) }
        finally { mutable.update { it.copy(loading = false) } }
    }
    fun connect() = scope.launch {
        if (state.value.loading || state.value.connected || state.value.closing) return@launch
        mutable.update { it.copy(loading = true, error = null) }
        try { startup.withLock { closingJob?.join(); startProcess() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { close(); error(failure) }
        finally { mutable.update { it.copy(loading = false) } }
    }
    private suspend fun startProcess() = withContext(Dispatchers.IO) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcodex_engine.so")
        check(binary.isFile && binary.canExecute()) { "当前包未包含可执行的原版核心。请按“运行核心/构建安卓核心.sh”生成对应 ABI 产物；此入口不会改用 Kotlin 内核。" }
        val config = SecureSettings(context).load()
        val endpoint = URI(config.endpoint)
        require(endpoint.scheme == "https" || config.allowLoopbackHttp && endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")) { "原版核心仅接受 HTTPS 或已允许的本机 HTTP 模型地址" }
        require(!endpoint.host.isNullOrBlank()) { "模型地址需要有效主机名" }
        require(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) { "模型地址不可包含凭证、查询或片段" }
        val args = mutableListOf("--listen", "stdio://", "-c", "cli_auth_credentials_store=\"keyring\"", "-c", "mcp_oauth_credentials_store=\"keyring\"", "-c", "shell_environment_policy.inherit=\"core\"")
        for (server in McpTools(context).connections()) {
            val mcpUrl = URI(server.url)
            require(mcpUrl.scheme == "https" && !mcpUrl.host.isNullOrBlank() && mcpUrl.rawUserInfo == null) { "MCP 连接需要不含凭证的 HTTPS 地址" }
            require(server.name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "MCP 连接标识不适用于原版配置" }
            args += listOf("-c", "mcp_servers.${server.name}.url=${JsonPrimitive(server.url)}")
        }
        val trust = AndroidTrustBundle.write(engineHome)
        val vault = OriginalVaultServer(context, scope).also { vaultServer = it }
        val env = System.getenv().toMutableMap().apply {
            put("CODEX_HOME", engineHome.absolutePath); put("HOME", engineHome.absolutePath)
            put("CODEX_ANDROID_VAULT_SOCKET", vault.path); put("CODEX_ANDROID_VAULT_HOST_PID", android.os.Process.myPid().toString())
            put("TMPDIR", context.cacheDir.absolutePath); put("SHELL", "/system/bin/sh")
            put("CODEX_CA_CERTIFICATE", trust.absolutePath); put("SSL_CERT_FILE", trust.absolutePath)
            remove("SSL_CERT_DIR")
            remove("OPENAI_API_KEY"); remove("CODEX_API_KEY"); remove("CODEX_ANDROID_API_KEY")
        }
        if (config.endpoint.trimEnd('/') != "https://api.openai.com/v1") {
            val base = JsonPrimitive(config.endpoint.trimEnd('/')).toString()
            args += listOf("-c", "model_provider=\"android_service\"", "-c", "model_providers.android_service={name=\"手机模型服务\",base_url=$base,wire_api=\"responses\",env_key=\"CODEX_ANDROID_API_KEY\"}")
            if (config.apiKey.isNotBlank()) env["CODEX_ANDROID_API_KEY"] = config.apiKey
        }
        val child = NativeCoreProcess(binary, args, env, workspace)
        process = child
        vault.authorize(child.pid)
        // 丢弃 stderr，避免上游错误带入令牌；绝不把协议/环境/API key 原样写日志。
        scope.launch(Dispatchers.IO) { runCatching { child.errors.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) { /* drain */ } } } }
        val rpc = AppServerConnection(child.input, child.output, scope, child::close)
        connection = rpc
        listener = scope.launch {
            try { rpc.messages.collect { message -> onMessage(rpc, message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (connection === rpc) {
                    completion?.completeExceptionally(failure)
                    close()
                    mutable.update { it.copy(error = "原版核心连接已中断，请重新启动后恢复会话") }
                }
            }
        }
        val api = OriginalCodexClient(rpc)
        api.initialize()
        if (config.apiKey.isNotBlank() && config.endpoint.trimEnd('/') == "https://api.openai.com/v1" && api.account().jsonObject["account"] == JsonNull) api.loginApiKey(config.apiKey)
        withContext(Dispatchers.Main.immediate) {
            check(connection === rpc && !state.value.closing) { "原版核心在初始化期间退出" }
            client = api
            mutable.update { it.copy(connected = true, status = "原版核心已连接 · 凭证由手机 Keystore 加密保存", model = config.model) }
        }
        refreshThreads(api, false)
        readAccount(api)
    }
    fun close() {
        if (state.value.closing) return
        completion?.completeExceptionally(IllegalStateException("原版核心已停止")); completion = null
        listener?.cancel(); listener = null
        val rpc = connection; val child = process; val vault = vaultServer
        connection = null; client = null; process = null; vaultServer = null
        mutable.update { OriginalState(model = it.model, closing = true, status = "正在关闭核心") }
        closingJob = scope.launch(Dispatchers.IO) {
            try { rpc?.close(); child?.close() }
            finally { runCatching { vault?.close() }; mutable.update { it.copy(closing = false, status = "原版核心已关闭") } }
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
        mutable.update { it.copy(threadId = thread.string("id"), turnId = active, items = items, historyCursor = null, model = thread.string("model", it.model), status = if (active == null) "会话已恢复" else "已恢复正在运行的回合") }
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
        val key = withContext(Dispatchers.IO) { SecureSettings(context).load().apiKey }
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
    private suspend fun readAccount(api: OriginalCodexClient) {
        val account = api.account().jsonObject["account"] as? JsonObject
        val label = when (account?.string("type")) { "chatgpt" -> "ChatGPT · ${account.string("email")} · ${account.string("planType")}"; "apiKey" -> "API 密钥"; else -> "尚未登录" }
        mutable.update { it.copy(account = label) }
    }
    fun send(text: String) {
        if (text.isBlank() || state.value.loading || state.value.working || !state.value.connected) return
        if (state.value.turnId != null) { error(IllegalStateException("已恢复的回合仍在运行，请先停止或追加指令")); return }
        if ((context.applicationContext as CodexApplication).controller.state.value.busy) { error(IllegalStateException("另一个任务正在运行")); return }
        mutable.update { it.copy(working = true, error = null) }
        try { context.startForegroundService(Intent(context, OriginalExecutionService::class.java).putExtra("prompt", text)) }
        catch (failure: Exception) { mutable.update { it.copy(working = false) }; error(failure) }
    }
    suspend fun runReserved(text: String) {
        if (!state.value.working) return
        val finished = CompletableDeferred<Unit>(); completion = finished
        var turnFinished = false
        try {
            val api = checkNotNull(client)
            if (state.value.threadId == null) selectThread(api.start(workspace.absolutePath, state.value.model).jsonObject.getValue("thread").jsonObject)
            val thread = requireNotNull(state.value.threadId)
            val response = api.turn(thread, text, SecureSettings(context).load().reasoning).jsonObject
            val turn = response["turn"] as? JsonObject
            // 可能在响应之前已收到 turn/completed，不能把已完成回合重新标为活动。
            if (!finished.isCompleted) mutable.update { it.copy(turnId = turn?.string("id") ?: it.turnId, status = "正在处理") }
            finished.await(); turnFinished = true
            refreshThreads(api, false)
        } catch (cancelled: CancellationException) { stop(); throw cancelled }
        catch (failure: Exception) {
            // 请求超时可能已有副作用；不自动再发 turn/start，关闭后让用户恢复检查。
            if (turnFinished) error(IllegalStateException("任务已结束，但刷新会话失败：${failure.message.orEmpty()}"))
            else { close(); error(IllegalStateException("任务状态无法确认，核心已关闭。请恢复会话检查已产生的修改。${failure.message.orEmpty()}")) }
        }
        finally { if (completion === finished) completion = null; mutable.update { it.copy(working = false) } }
    }
    fun steer(text: String) = operation { api -> api.steer(requireNotNull(state.value.threadId), requireNotNull(state.value.turnId), text) }
    fun stop() {
        if (stopping?.isActive == true) return
        stopping = scope.launch {
            val snapshot = state.value
            val rpc = connection
            try {
                if (snapshot.threadId != null && snapshot.turnId != null) {
                    mutable.update { it.copy(status = "正在停止任务") }
                    withTimeout(10_000) {
                        client?.interrupt(snapshot.threadId, snapshot.turnId)
                        state.first { it.turnId != snapshot.turnId || !it.connected }
                    }
                } else if (snapshot.working) { close(); return@launch }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (connection === rpc && state.value.turnId == snapshot.turnId) { close(); error(IllegalStateException("核心未及时响应停止，已结束本机核心进程；已完成的修改仍保留")) }
            } catch (failure: Exception) {
                if (connection === rpc && state.value.turnId == snapshot.turnId) close()
                error(failure)
            }
        }
    }
    fun answer(request: ServerMessage.Request, result: JsonElement) = scope.launch {
        // resolved 通知已移除的请求不可再次回传；ID 类型（数字/字符串）原样保留。
        if (state.value.requests.none { it.id == request.id }) return@launch
        try { connection?.respond(request.id, result); mutable.update { it.copy(requests = it.requests.filterNot { pending -> pending.id == request.id }) } }
        catch (failure: Exception) { error(failure) }
    }
    private suspend fun onMessage(rpc: AppServerConnection, message: ServerMessage) {
        when (message) {
            is ServerMessage.Request -> {
                if (message.method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request")) {
                    if (state.value.requests.size >= 32) rpc.reject(message.id, -32000, "待处理交互数量达到上限")
                    else mutable.update { it.copy(requests = it.requests + message) }
                } else if (message.method == "item/permissions/requestApproval") {
                    // 该客户端尚未提供细粒度权限 UI，返回空授权；不可把 approve 普通按钮映射成全部权限。
                    mutable.update { it.copy(status = "细粒度权限尚未接入，本次未授予额外权限") }
                    rpc.respond(message.id, buildJsonObject { put("permissions", buildJsonObject {}); put("scope", "turn") })
                } else rpc.reject(message.id)
            }
            is ServerMessage.Notification -> notification(message.method, message.params as? JsonObject ?: return)
        }
    }
    private suspend fun notification(method: String, params: JsonObject) {
        if (method == "account/login/completed") {
            if (state.value.loginId != null && params.string("loginId") != state.value.loginId) return
            val success = params["success"]?.jsonPrimitive?.booleanOrNull == true
            mutable.update { it.copy(loginId = null, loginUrl = null, loginCode = null, error = if (success) null else params.string("error", "登录未完成")) }
            // 不在读取循环里等待另一条 RPC，否则响应无人读取而死锁。
            if (success) scope.launch { runSuspendCatching { client?.let { readAccount(it) } }.onFailure(::error) }
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
            scope.launch { runSuspendCatching { client?.let { readAccount(it) } }.onFailure(::error) }; return
        }
        if (method == "serverRequest/resolved") { mutable.update { it.copy(requests = it.requests.filterNot { r -> r.id == params["requestId"] }) }; return }
        if (params.string("threadId").isNotBlank() && params.string("threadId") != state.value.threadId) return
        when (method) {
            "turn/started" -> mutable.update { it.copy(turnId = (params["turn"] as? JsonObject)?.string("id"), status = "正在处理") }
            "turn/completed" -> {
                val turn = params["turn"] as? JsonObject
                val failure = (turn?.get("error") as? JsonObject)?.string("message")
                mutable.update { it.copy(turnId = null, requests = it.requests.filterNot { r -> (r.params as? JsonObject)?.string("turnId") == turn?.string("id") }, status = when (turn?.string("status")) { "failed" -> "任务失败"; "interrupted" -> "任务已停止"; else -> "任务完成" }, error = failure?.takeIf(String::isNotBlank)) }
                completion?.complete(Unit)
            }
            "item/fileChange/patchUpdated" -> mutable.update { it.copy(items = OriginalThreadView.patch(it.items, params.string("itemId"), params["changes"] as? JsonArray ?: buildJsonArray {})) }
            "turn/diff/updated" -> mutable.update { it.copy(report = params.string("diff")) }
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
