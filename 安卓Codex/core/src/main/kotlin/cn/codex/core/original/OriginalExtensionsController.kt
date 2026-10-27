package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

/** 主线程调用；每次绑定新连接递增代次，旧请求和旧详情不能覆盖新状态。 */
data class OriginalExtensionsState(
    val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val plugins: List<OriginalPlugin> = emptyList(), val marketplaceErrors: List<String> = emptyList(),
    val hooks: List<JsonObject> = emptyList(), val hookRuns: List<OriginalItem> = emptyList(),
    val detail: JsonObject? = null, val detailKey: String? = null,
    val appsNeedingAuth: List<JsonObject> = emptyList(), val error: String? = null, val status: String = "尚未加载扩展"
)
class OriginalExtensionsController(private val scope: CoroutineScope, private val cwd: String, private val activeTask: () -> Boolean) {
    private val mutable = MutableStateFlow(OriginalExtensionsState())
    val state = mutable.asStateFlow()
    private var client: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    fun bind(client: OriginalCodexClient?) {
        generation++; job?.cancel(); job = null; this.client = client
        mutable.value = OriginalExtensionsState(connected = client != null)
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun closeDetail() { mutable.update { it.copy(detail = null, detailKey = null) } }
    fun dismissAuth() { mutable.update { it.copy(appsNeedingAuth = emptyList()) } }
    private fun operation(mutating: Boolean = false, block: suspend (OriginalCodexClient, Long) -> Unit) {
        if (state.value.busy) return
        val api = client ?: return
        val stamp = generation
        if (mutating && activeTask()) { mutable.update { it.copy(error = "任务运行中，请先停止任务后修改扩展") }; return }
        mutable.update { it.copy(busy = true, mutating = mutating, error = null) }
        job = scope.launch {
            try { block(api, stamp) }
            catch (cancelled: CancellationException) {
                // withTimeout 的超时也属于 CancellationException，必须报告未知安装结果。
                if (cancelled is TimeoutCancellationException && stamp == generation) mutable.update { it.copy(error = if (mutating) "扩展请求超时，结果需核查。请刷新实际安装状态，勿直接重复安装。" else "扩展请求超时，请稍后刷新", status = "请求超时，请刷新核查") }
                else throw cancelled
            }
            catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = if (mutating) "扩展操作未能确认结果，请刷新核查：${failure.message.orEmpty()}" else failure.message ?: "扩展操作失败", status = if (mutating) "操作未确认，请刷新核查" else it.status) } }
            finally { if (stamp == generation) mutable.update { it.copy(busy = false, mutating = false) } }
        }
    }
    private suspend fun load(api: OriginalCodexClient, stamp: Long, fresh: Boolean) {
        val errors = mutableListOf<String>()
        suspend fun read(label: String, block: suspend () -> JsonElement): JsonObject? = try { block().jsonObject }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (cancelled !is TimeoutCancellationException) throw cancelled
            errors += "$label 请求超时"; null
        } catch (failure: Exception) { errors += "$label：${failure.message.orEmpty()}"; null }
        // 市场离线时仍读取本机安装和 Hooks，避免目录失败让管理入口整体失效。
        val catalog = read("插件目录") { api.pluginCatalog(cwd, fresh) }?.let(OriginalExtensions::catalog) ?: OriginalCatalog(emptyList(), emptyList())
        val installed = read("已安装插件") { api.installedPlugins(cwd) }?.let(OriginalExtensions::catalog) ?: OriginalCatalog(emptyList(), emptyList())
        val combined = OriginalExtensions.combine(catalog, installed)
        val hooks = (read("Hooks") { api.hooks(cwd, null) }?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (stamp == generation) mutable.update { it.copy(plugins = combined.plugins, marketplaceErrors = (combined.errors + errors).distinct(), hooks = hooks, status = "${combined.plugins.count { p -> p.installed }} 个已安装插件 · ${combined.plugins.size} 个目录条目" + if (errors.isNotEmpty()) " · 部分来源加载失败" else "") }
    }
    fun refresh(fresh: Boolean = false) = operation { api, stamp -> load(api, stamp, fresh) }
    fun detail(plugin: OriginalPlugin) = operation { api, stamp ->
        val result = api.pluginDetail(plugin).jsonObject.getValue("plugin").jsonObject
        if (stamp == generation) mutable.update { it.copy(detail = result, detailKey = plugin.key) }
    }
    fun install(plugin: OriginalPlugin) = operation(mutating = true) { api, stamp ->
        // 重新检查当前目录中的状态；不信任已打开确认框中的旧对象。
        val current = state.value.plugins.firstOrNull { it.key == plugin.key } ?: error("插件目录已变化，请刷新后重试")
        require(current.installBlock == null) { current.installBlock.orEmpty() }
        mutable.update { it.copy(status = "正在安装 ${current.displayName}") }
        val result = api.installPlugin(current, UUID.randomUUID().toString()).jsonObject
        if (stamp != generation) return@operation
        mutable.update { it.copy(appsNeedingAuth = (result["appsNeedingAuth"] as? JsonArray).orEmpty().mapNotNull { app -> app as? JsonObject }, detail = null, detailKey = null, status = "安装请求已完成，正在读取实际状态") }
        load(api, stamp, false)
    }
    fun uninstall(plugin: OriginalPlugin) = operation(mutating = true) { api, stamp ->
        val current = state.value.plugins.firstOrNull { it.key == plugin.key && it.installed } ?: error("已安装插件状态已变化，请刷新后重试")
        api.uninstallPlugin(current.id)
        if (stamp != generation) return@operation
        mutable.update { it.copy(detail = null, detailKey = null, status = "卸载请求已完成，正在读取实际状态") }
        load(api, stamp, false)
    }
    fun recordHook(run: JsonObject) {
        val item = OriginalExtensions.hookRun(run)
        if (item.id.isBlank()) return
        mutable.update { it.copy(hookRuns = OriginalThreadView.upsert(it.hookRuns, item).takeLast(200)) }
    }
}
