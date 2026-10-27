package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class OriginalImportRun(val id: String, val results: List<JsonObject>, val completed: Boolean)
data class OriginalMigrationState(val items: List<JsonObject> = emptyList(), val connectors: List<JsonObject> = emptyList(),
    val histories: List<JsonObject> = emptyList(), val importedConnectors: List<JsonObject> = emptyList(), val runs: Map<String, OriginalImportRun> = emptyMap(), val activeImport: String? = null,
    val config: JsonObject? = null, val configSnapshot: OriginalConfigSnapshot? = null, val configWrite: JsonObject? = null,
    val summary: JsonObject? = null, val diff: JsonObject? = null, val auth: JsonObject? = null, val files: List<JsonObject> = emptyList())
class OriginalMigrationController(scope: CoroutineScope, private val cwd: String, activeTask: () -> Boolean, lifecycle: OriginalMutationLifecycle) {
    val operations = OriginalOperationRunner(scope, activeTask, lifecycle)
    private val mutable = MutableStateFlow(OriginalMigrationState())
    val state = mutable.asStateFlow()
    fun bind(api: OriginalCodexClient?) { operations.bind(api); mutable.value = OriginalMigrationState() }
    private fun update(stamp: Long, action: (OriginalMigrationState) -> OriginalMigrationState) { if (operations.current(stamp)) mutable.update(action) }
    fun importProgress(params: JsonObject, completed: Boolean) {
        val id = params.string("importId"); if (id.isBlank()) return
        val results = (params["itemTypeResults"] as? JsonArray).orEmpty().map { it.jsonObject }
        mutable.update { previous ->
            val old = previous.runs[id]
            if (old?.completed == true && !completed) return@update previous
            val run = OriginalImportRun(id, results, completed || old?.completed == true)
            previous.copy(runs = ((previous.runs - id) + (id to run)).entries.toList().takeLast(32).associate { it.toPair() })
        }
    }
    fun detect(home: Boolean, paths: String, days: String, max: String, source: String, migrationSource: String) = operations.run { api, stamp ->
        val roots = paths.lineSequence().filter { it.isNotBlank() }.toList()
        val age = days.takeIf { it.isNotBlank() }?.let { it.toLong().also { value -> require(value >= 0) } }
        val count = max.takeIf { it.isNotBlank() }?.let { it.toLong().also { value -> require(value >= 0) } }
        val response = api.detectExternal(home, roots, age, count, source.takeIf { it.isNotBlank() }, migrationSource.takeIf { it.isNotBlank() }).jsonObject
        update(stamp) { it.copy(items = response.getValue("items").jsonArray.map { it.jsonObject }, connectors = response.getValue("connectors").jsonArray.map { it.jsonObject }) }
        operations.notice(stamp, "已检测可迁移内容，请逐项选择")
    }
    fun importSelected(selected: List<JsonObject>, source: String, provider: String, migrationSource: String) = operations.run(true) { api, stamp ->
        require(selected.isNotEmpty() && selected.all { it in state.value.items }) { "选择已变化，请重新检测" }
        update(stamp) { it.copy(activeImport = null) }
        val response = api.importExternal(JsonArray(selected), source.takeIf { it.isNotBlank() }, provider.takeIf { it.isNotBlank() }, migrationSource.takeIf { it.isNotBlank() }).jsonObject
        val id = response.string("importId"); require(id.isNotBlank()) { "导入响应缺少标识，请核查实际文件" }
        update(stamp) { it.copy(activeImport = id) }; operations.notice(stamp, "导入正在后台处理，等待完成通知")
        withTimeout(30 * 60 * 1000L) { state.first { it.runs[id]?.completed == true } }
        if (!operations.current(stamp)) return@run
        val result = state.value.runs.getValue(id)
        val failed = result.results.sumOf { (it["failures"] as? JsonArray).orEmpty().size }
        operations.notice(stamp, if (failed == 0) "导入已结束，请核查迁移结果" else "导入已结束，有 $failed 项失败，请查看详情")
        histories(api, stamp)
    }
    private suspend fun histories(api: OriginalCodexClient, stamp: Long) { val response = api.importHistories().jsonObject; update(stamp) { it.copy(histories = response.getValue("data").jsonArray.map { it.jsonObject }, importedConnectors = (response["connectors"] as? JsonArray).orEmpty().map { it.jsonObject }) } }
    fun readHistories() = operations.run { api, stamp -> histories(api, stamp); operations.notice(stamp, "已读取迁移历史") }
    fun recordHistory(provider: String, results: String) = operations.run(true) { api, stamp ->
        require(provider.isNotBlank()) { "请填写迁移提供方" }
        val response = api.recordImport(provider, OriginalRemainingContracts.historyResults(results)).jsonObject
        operations.notice(stamp, "已登记结果：${response.string("importId")}"); histories(api, stamp)
    }
    private suspend fun config(api: OriginalCodexClient, stamp: Long) { val response = api.config(cwd).jsonObject; update(stamp) { it.copy(config = response["config"] as? JsonObject, configSnapshot = OriginalConfigEdits.snapshot(response)) } }
    fun readConfig() = operations.run { api, stamp -> config(api, stamp); operations.notice(stamp, "已读取活动用户配置版本") }
    fun writeValue(snapshot: OriginalConfigSnapshot, key: String, text: String, strategy: String) = operations.run(true) { api, stamp ->
        require(snapshot == state.value.configSnapshot) { "配置版本已变化，请重新读取" }; require(key.isNotBlank() && strategy in setOf("upsert", "replace"))
        val value = Json.parseToJsonElement(text)
        val result = api.writeConfigValue(snapshot, key, value, strategy).jsonObject
        update(stamp) { it.copy(configWrite = result) }; config(api, stamp)
        operations.notice(stamp, if (result.string("status") == "okOverridden") "已保存，但更高配置层覆盖了此项，请核查有效配置" else "已保存并重新读取有效配置")
    }
    fun summary(id: String, path: String) = operations.run { api, stamp ->
        require(id.isNotBlank() || path.isNotBlank()) { "请填写会话标识或记录路径" }
        val result = api.conversationSummary(id.takeIf { it.isNotBlank() }, path.takeIf { it.isNotBlank() }).jsonObject.getValue("summary").jsonObject
        update(stamp) { it.copy(summary = result) }; operations.notice(stamp, "已读取会话摘要")
    }
    fun diff(path: String) = operations.run { api, stamp -> val result = api.remoteDiff(path.ifBlank { cwd }).jsonObject; update(stamp) { it.copy(diff = result) }; operations.notice(stamp, "已读取与远端的差异") }
    fun auth(refresh: Boolean, includeToken: Boolean) = operations.run { api, stamp -> val result = api.authStatus(refresh, includeToken).jsonObject; update(stamp) { it.copy(auth = result) }; operations.notice(stamp, "已读取账号认证状态") }
    fun hideToken() { mutable.update { it.copy(auth = it.auth?.let { data -> JsonObject(data - "authToken") }) } }
    fun search(query: String, roots: String) = operations.run { api, stamp ->
        require(query.isNotBlank()); val paths = roots.lineSequence().filter { it.isNotBlank() }.toList().ifEmpty { listOf(cwd) }
        val response = api.fuzzySearch(query, paths, java.util.UUID.randomUUID().toString()).jsonObject
        update(stamp) { it.copy(files = response.getValue("files").jsonArray.map { it.jsonObject }) }; operations.notice(stamp, "文件查找已返回")
    }
}
