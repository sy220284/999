package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class OriginalMcpEditor(val generation: Long, val snapshot: OriginalConfigSnapshot, val name: String?, val original: JsonObject, val suggestedName: String = "")
data class OriginalMcpState(
    val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val config: JsonObject? = null, val snapshot: OriginalConfigSnapshot? = null,
    val inventory: List<JsonObject> = emptyList(), val nextCursor: String? = null,
    val inventoryThread: String? = null, val inventoryError: String? = null,
    val editor: OriginalMcpEditor? = null, val error: String? = null, val notice: String = "尚未读取服务"
) {
    val entries get() = OriginalMcpConfig.entries(config, inventory)
}

/** 页面只管理原版配置；保存和重连分别报告结果，不把保存成功当作连接成功。 */
class OriginalMcpController(private val scope: CoroutineScope, private val cwd: String,
    private val activeTask: () -> Boolean, private val threadId: () -> String?) {
    private val mutable = MutableStateFlow(OriginalMcpState())
    val state = mutable.asStateFlow()
    private var client: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    private val cursors = mutableSetOf<String>()
    fun bind(api: OriginalCodexClient?) {
        generation++; job?.cancel(); client = api; cursors.clear()
        mutable.value = OriginalMcpState(connected = api != null)
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun closeEditor() { if (!state.value.mutating) mutable.update { it.copy(editor = null) } }
    fun edit(name: String? = null) {
        val snapshot = state.value.snapshot ?: return
        if (state.value.busy) return
        val entry = name?.let { value -> state.value.entries.singleOrNull { it.name == value } }
        if (name != null && entry?.editable != true) return
        mutable.update { it.copy(editor = OriginalMcpEditor(generation, snapshot, name, entry?.userConfig ?: buildJsonObject {}), error = null) }
    }
    fun importConnection(name: String, url: String) {
        val snapshot = state.value.snapshot ?: return
        if (state.value.busy) return
        if (state.value.entries.any { it.name == name }) { mutable.update { it.copy(error = "已有同名配置，请在列表中编辑，或新增时使用其他标识") }; return }
        mutable.update { it.copy(editor = OriginalMcpEditor(generation, snapshot, null, buildJsonObject { put("url", url) }, name), error = null) }
    }
    private fun operation(mutating: Boolean = false, action: suspend (OriginalCodexClient, Long) -> Unit) {
        if (state.value.busy) return
        val api = client ?: return
        if (mutating && activeTask()) { mutable.update { it.copy(error = "请先停止任务后修改或重新连接服务") }; return }
        val stamp = generation
        mutable.update { it.copy(busy = true, mutating = mutating, error = null) }
        job = scope.launch {
            try { action(api, stamp) }
            catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (stamp == generation) mutable.update { it.copy(error = "服务请求超时，请刷新核查实际状态；不会自动重复提交") }
            }
            catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = failure.message ?: "服务操作失败，请刷新核查") } }
            finally { if (stamp == generation) mutable.update { it.copy(busy = false, mutating = false) } }
        }
    }
    private suspend fun load(api: OriginalCodexClient, stamp: Long) {
        var failure: String? = null
        val config = try { api.config(cwd).jsonObject }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (cancelled !is TimeoutCancellationException) throw cancelled; failure = "配置读取超时"; null }
        catch (error: Exception) { failure = "配置读取失败：${error.message.orEmpty()}"; null }
        val thread = threadId()
        var inventoryFailure: String? = null
        val page = try { api.mcp(threadId = thread).jsonObject }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (cancelled !is TimeoutCancellationException) throw cancelled; inventoryFailure = "服务状态读取超时"; null }
        catch (error: Exception) { inventoryFailure = "服务状态读取失败：${error.message.orEmpty()}"; null }
        if (stamp != generation) return
        cursors.clear()
        mutable.update { it.copy(config = config, snapshot = config?.let(OriginalConfigEdits::snapshot), inventory = data(page),
            nextCursor = page?.string("nextCursor")?.ifBlank { null }, inventoryThread = thread, inventoryError = inventoryFailure,
            notice = if (config == null) "配置未读取，暂不能修改" else if (OriginalConfigEdits.snapshot(config) == null) "没有可写的用户配置版本，当前只读" else "管理手机上的用户配置 · 保存后重新连接并核查状态", error = failure) }
    }
    private fun data(page: JsonObject?) = (page?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun refresh() = operation { api, stamp -> load(api, stamp) }
    fun more() = operation { api, stamp ->
        val before = state.value
        val cursor = before.nextCursor ?: return@operation
        require(before.inventoryThread == threadId()) { "当前会话已变化，请重新刷新服务" }
        val page = api.mcp(cursor, before.inventoryThread).jsonObject
        if (stamp != generation) return@operation
        cursors += cursor
        val next = page.string("nextCursor").ifBlank { null }
        mutable.update { it.copy(inventory = (it.inventory + data(page)).associateBy { server -> server.string("name") }.values.toList(),
            nextCursor = next?.takeUnless(cursors::contains), inventoryError = if (next != null && next in cursors) "服务返回了重复分页游标，请刷新后重试" else null) }
    }
    private suspend fun persist(api: OriginalCodexClient, stamp: Long, snapshot: OriginalConfigSnapshot, edit: JsonObject) {
        val response = api.writeConfig(snapshot, edit).jsonObject
        if (stamp != generation) return
        val status = response.string("status")
        val message = when (status) {
            "ok" -> "用户配置已保存"
            "okOverridden" -> "用户配置已保存，但有效值被更高优先级配置覆盖：" + (response["overriddenMetadata"] as? JsonObject)?.string("message").orEmpty()
            else -> "写入状态未知，请核查配置"
        }
        mutable.update { it.copy(editor = null, notice = message) }
        if (status !in setOf("ok", "okOverridden")) { load(api, stamp); if (stamp == generation) mutable.update { it.copy(error = message) }; return }
        var reloadError: String? = null
        try { api.reloadMcp() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (cancelled !is TimeoutCancellationException) throw cancelled; reloadError = "重新连接请求超时，结果需刷新核查" }
        catch (failure: Exception) { reloadError = "重新连接失败：${failure.message.orEmpty()}" }
        load(api, stamp)
        if (stamp == generation) mutable.update { it.copy(notice = "$message。" + if (reloadError == null) "已请求重新连接，请以实际状态为准。" else reloadError, error = it.error ?: reloadError) }
    }
    fun save(editor: OriginalMcpEditor, name: String, config: JsonObject) = operation(mutating = true) { api, stamp ->
        require(editor.generation == stamp && editor == state.value.editor && editor.snapshot == state.value.snapshot) { "配置或连接已变化，请重新打开编辑" }
        if (editor.name == null) require(state.value.entries.none { it.name == name }) { "这个标识已存在，请选择其他名称" }
        else require(editor.name == name) { "编辑时不能改变服务标识" }
        persist(api, stamp, editor.snapshot, OriginalMcpConfig.edit(name, config))
    }
    fun toggle(approved: OriginalMcpEntry, snapshot: OriginalConfigSnapshot) = operation(mutating = true) { api, stamp ->
        val current = current(approved, snapshot)
        val fields = JsonObject(current.userConfig!! + ("enabled" to JsonPrimitive(!approved.enabled)))
        persist(api, stamp, snapshot, OriginalMcpConfig.edit(current.name, fields))
    }
    fun delete(approved: OriginalMcpEntry, snapshot: OriginalConfigSnapshot) = operation(mutating = true) { api, stamp ->
        current(approved, snapshot)
        persist(api, stamp, snapshot, OriginalMcpConfig.edit(approved.name, null))
    }
    private fun current(approved: OriginalMcpEntry, snapshot: OriginalConfigSnapshot): OriginalMcpEntry {
        require(snapshot == state.value.snapshot) { "配置已刷新，请重新确认" }
        val current = state.value.entries.singleOrNull { it.name == approved.name }
        require(current != null && current.editable && current.userConfig == approved.userConfig && current.effectiveConfig == approved.effectiveConfig) { "服务配置已变化，请重新确认" }
        return current
    }
    fun reconnect() = operation(mutating = true) { api, stamp ->
        api.reloadMcp(); load(api, stamp)
        if (stamp == generation) mutable.update { it.copy(notice = "已请求重新连接，请以实际状态为准") }
    }
    fun runtimeChanged(thread: String?, name: String, status: String, error: String?) {
        if (thread != state.value.inventoryThread) return
        // 通知只带启动进度，不能用它冒充完整目录或授权状态；保留原清单并提示刷新。
        mutable.update { it.copy(notice = "$name：${mcpRuntimeTitle(status)}" + error?.let { detail -> " · $detail" }.orEmpty()) }
    }
}

fun mcpRuntimeTitle(status: String) = when (status) {
    "notStarted" -> "尚未连接"; "starting" -> "连接中"; "connected" -> "已连接"; "ready" -> "服务已就绪"
    "authenticationRequired" -> "需要授权"; "failed" -> "连接失败"; "cancelled" -> "连接已取消"; "disabled" -> "已停用"; else -> "状态待刷新"
}
