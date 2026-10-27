package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File

data class OriginalLibraryState(val markets: List<JsonObject> = emptyList(), val shares: List<JsonObject> = emptyList(),
    val marketResult: JsonObject? = null, val shareResult: JsonObject? = null, val apps: OriginalSessionPage<JsonObject> = OriginalSessionPage(),
    val appThread: String? = null, val installed: List<JsonObject> = emptyList(), val appDetails: List<JsonObject> = emptyList(), val missingApps: List<String> = emptyList())
class OriginalLibraryController(scope: CoroutineScope, private val cwd: String, private val threadId: () -> String?, activeTask: () -> Boolean, lifecycle: OriginalMutationLifecycle) {
    val operations = OriginalOperationRunner(scope, activeTask, lifecycle)
    private val mutable = MutableStateFlow(OriginalLibraryState())
    val state = mutable.asStateFlow()
    fun bind(api: OriginalCodexClient?) { operations.bind(api); mutable.value = OriginalLibraryState() }
    private fun update(stamp: Long, change: (OriginalLibraryState) -> OriginalLibraryState) { if (operations.current(stamp)) mutable.update(change) }
    private suspend fun markets(api: OriginalCodexClient, stamp: Long) {
        val response = api.pluginCatalog(cwd, true).jsonObject
        update(stamp) { it.copy(markets = (response["marketplaces"] as? JsonArray).orEmpty().map { it.jsonObject }) }
    }
    fun refreshMarkets() = operations.run { api, stamp -> markets(api, stamp); operations.notice(stamp, "已读取插件市场") }
    fun addMarket(source: String, ref: String, sparse: String) = operations.run(true) { api, stamp ->
        require(source.isNotBlank()) { "请填写市场来源" }
        val result = api.addMarketplace(source, ref.takeIf { it.isNotBlank() }, sparse.lineSequence().filter { it.isNotBlank() }.toList().takeIf { it.isNotEmpty() }).jsonObject
        update(stamp) { it.copy(marketResult = result) }; markets(api, stamp); operations.notice(stamp, if (result["alreadyAdded"]?.jsonPrimitive?.booleanOrNull == true) "市场已经存在，已刷新" else "市场已添加")
    }
    fun removeMarket(name: String) = operations.run(true) { api, stamp ->
        require(name.isNotBlank()); val result = api.removeMarketplace(name).jsonObject
        update(stamp) { it.copy(marketResult = result) }; markets(api, stamp); operations.notice(stamp, "已移除市场，请核查相关插件")
    }
    fun upgradeMarket(name: String?) = operations.run(true) { api, stamp ->
        val result = api.upgradeMarketplace(name).jsonObject; update(stamp) { it.copy(marketResult = result) }; markets(api, stamp)
        operations.notice(stamp, if ((result["errors"] as? JsonArray).orEmpty().isEmpty()) "市场升级已返回，请核查升级目录" else "部分市场升级失败，请查看错误")
    }
    fun reconcile() = operations.run(true) { api, stamp ->
        val result = api.reconcilePlugins("android_user_requested").jsonObject; update(stamp) { it.copy(marketResult = result) }; markets(api, stamp)
        operations.notice(stamp, "插件对齐已返回，请查看变更和失败项目")
    }
    private suspend fun shares(api: OriginalCodexClient, stamp: Long) { val response = api.pluginShares().jsonObject; update(stamp) { it.copy(shares = response.getValue("data").jsonArray.map { it.jsonObject }) } }
    fun refreshShares() = operations.run { api, stamp -> shares(api, stamp); operations.notice(stamp, "已读取分享列表") }
    fun saveShare(path: String, id: String, visibility: String, targets: String, preserveAccess: Boolean = false) = operations.run(true) { api, stamp ->
        require(File(path).isAbsolute && File(path).isDirectory) { "请选择手机上实际存在的插件目录" }
        require(visibility in setOf("PRIVATE", "UNLISTED", "LISTED"))
        val result = api.savePluginShare(path, id.takeIf { it.isNotBlank() }, visibility.takeUnless { id.isNotBlank() && preserveAccess }, if (id.isNotBlank() && preserveAccess) null else OriginalRemainingContracts.targets(targets)).jsonObject
        update(stamp) { it.copy(shareResult = result) }; shares(api, stamp); operations.notice(stamp, "插件分享已保存")
    }
    fun updateShare(id: String, visibility: String, targets: String) = operations.run(true) { api, stamp ->
        require(id.isNotBlank() && visibility in setOf("PRIVATE", "UNLISTED", "LISTED"))
        val result = api.updatePluginShare(id, visibility, OriginalRemainingContracts.targets(targets)).jsonObject
        update(stamp) { it.copy(shareResult = result) }; shares(api, stamp); operations.notice(stamp, "已更新分享对象与可见性")
    }
    fun checkoutShare(id: String) = operations.run(true) { api, stamp -> require(id.isNotBlank()); val result = api.checkoutPluginShare(id).jsonObject; update(stamp) { it.copy(shareResult = result) }; shares(api, stamp); operations.notice(stamp, "分享插件已检出到返回的本机目录") }
    fun deleteShare(id: String) = operations.run(true) { api, stamp -> require(id.isNotBlank()); api.deletePluginShare(id); shares(api, stamp); operations.notice(stamp, "分享已删除") }
    fun loadApps(more: Boolean = false) {
        val thread = if (more) state.value.appThread else threadId()
        operations.run { api, stamp ->
            val base: OriginalSessionPage<JsonObject> = if (more) state.value.apps else OriginalSessionPage()
            val cursor = if (more) base.nextCursor ?: return@run else null
            val response = api.apps(cursor, thread, !more).jsonObject
            val page = base.append(response.getValue("data").jsonArray.map { it.jsonObject }, response.string("nextCursor").ifBlank { null }, cursor) { it.string("id") }
            update(stamp) { it.copy(apps = page, appThread = thread) }; operations.notice(stamp, if (page.repeatedCursor) "目录游标重复，已停止继续分页" else "已读取 ${page.entries.size} 个连接应用")
        }
    }
    fun loadInstalled() { val thread = threadId(); operations.run { api, stamp -> val response = api.installedApps(thread, true).jsonObject; update(stamp) { it.copy(installed = response.getValue("apps").jsonArray.map { it.jsonObject }) }; operations.notice(stamp, "已读取已安装应用的实际调用状态") } }
    fun appDetail(ids: List<String>) { val thread = threadId(); operations.run { api, stamp -> require(ids.isNotEmpty()); val response = api.readApps(ids, thread).jsonObject; update(stamp) { it.copy(appDetails = response.getValue("apps").jsonArray.map { it.jsonObject }, missingApps = response.getValue("missingAppIds").jsonArray.map { it.jsonPrimitive.content }) }; operations.notice(stamp, "已读取应用说明和工具目录") } }
}
