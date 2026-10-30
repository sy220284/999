package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 主线程调用；每次绑定新连接递增代次，旧请求和旧详情不能覆盖新状态。 */
data class OriginalHookDraft(val snapshot: OriginalConfigSnapshot, val hooks: JsonObject)

data class OriginalExtensionsState(
    val skills: List<JsonObject> = emptyList(), val skillErrors: List<String> = emptyList(),
    val skillNotice: String = "尚未扫描技能", val skillsDirty: Boolean = false, val skillsRevision: Long = 0,
    val extraRoots: List<String>? = null, val skillContents: String? = null, val skillTitle: String = "",
    val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val plugins: List<OriginalPlugin> = emptyList(), val marketplaceErrors: List<String> = emptyList(),
    val hooks: List<JsonObject> = emptyList(), val hookRuns: List<OriginalItem> = emptyList(),
    val userHooks: JsonObject = buildJsonObject {}, val rulesEditor: OriginalHookDraft? = null,
    val configSnapshot: OriginalConfigSnapshot? = null,
    val configNotice: String = "配置尚未读取",
    val detail: JsonObject? = null, val detailKey: String? = null,
    val appsNeedingAuth: List<JsonObject> = emptyList(), val error: String? = null, val status: String = "尚未加载扩展"
)
class OriginalExtensionsController(private val scope: CoroutineScope, private val cwd: String, private val mutationLifecycle: OriginalMutationLifecycle = OriginalMutationLifecycle.None, private val activeTask: () -> Boolean) {
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
        val ticket = if (mutating) try { mutationLifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = "无法托管配置操作：${failure.message.orEmpty()}") }; return } else null
        mutable.update { it.copy(busy = true, mutating = mutating, error = null) }
        job = scope.launch {
            var confirmed = false
            try { block(api, stamp); confirmed = stamp == generation && state.value.error == null }
            catch (cancelled: CancellationException) {
                // withTimeout 的超时也属于 CancellationException，必须报告未知安装结果。
                if (cancelled is TimeoutCancellationException && stamp == generation) mutable.update { it.copy(error = if (mutating) "扩展请求超时，结果需核查。请刷新实际配置与安装状态，勿直接重复提交。" else "扩展请求超时，请稍后刷新", status = "请求超时，请刷新核查") }
                else throw cancelled
            }
            catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = if (mutating) "扩展操作未能确认结果，请刷新核查：${failure.message.orEmpty()}" else failure.message ?: "扩展操作失败", status = if (mutating) "操作未确认，请刷新核查" else it.status) } }
            finally {
                try { if (ticket != null) mutationLifecycle.finish(ticket, confirmed && stamp == generation) }
                catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = "操作记录保存失败，请核查实际配置：${failure.message.orEmpty()}") } }
                finally { if (stamp == generation) mutable.update { it.copy(busy = false, mutating = false) } }
            }
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
        val configResponse = read("用户配置") { api.config() }
        val config = configResponse?.let(OriginalConfigEdits::snapshot)
        val userHooks = ((configResponse?.let(OriginalConfigEdits::userLayer)?.get("config") as? JsonObject)?.get("hooks") as? JsonObject) ?: buildJsonObject {}
        if (stamp == generation) mutable.update { it.copy(userHooks = userHooks, configSnapshot = config, configNotice = if (config == null) "没有可写的用户配置版本，请刷新或在原版中初始化配置" else "配置修改将检查版本，防止覆盖其他修改", plugins = combined.plugins, marketplaceErrors = (combined.errors + errors).distinct(), hooks = hooks, status = "${combined.plugins.count { p -> p.installed }} 个已安装插件 · ${combined.plugins.size} 个目录条目" + if (errors.isNotEmpty()) " · 部分来源加载失败" else "") }
    }
    fun refresh(fresh: Boolean = false) = operation { api, stamp -> load(api, stamp, fresh) }
    fun detail(plugin: OriginalPlugin) = operation { api, stamp ->
        val result = api.pluginDetail(plugin).jsonObject.getValue("plugin").jsonObject
        if (stamp == generation) mutable.update { it.copy(detail = result, detailKey = plugin.key) }
    }
    fun skillsChanged() { mutable.update { it.copy(skillsDirty = true, skillsRevision = it.skillsRevision + 1) } }
    fun closeSkill() { mutable.update { it.copy(skillContents = null, skillTitle = "") } }
    private suspend fun loadSkills(api: OriginalCodexClient, stamp: Long) {
        val revision = state.value.skillsRevision
        val response = api.skills(cwd, forceReload = true).jsonObject
        val entry = response.getValue("data").jsonArray.map { it.jsonObject }.singleOrNull { it.string("cwd") == cwd }
            ?: error("技能响应未包含当前工作目录")
        val skills = entry.getValue("skills").jsonArray.map { it.jsonObject }
        val errors = (entry["errors"] as? JsonArray).orEmpty().map { val error = it.jsonObject; error.string("path") + "：" + error.string("message") }
        if (stamp == generation) mutable.update { it.copy(skills = skills, skillErrors = errors, skillsDirty = it.skillsRevision != revision, skillNotice = "已扫描 ${skills.size} 个技能") }
    }
    fun refreshSkills() = operation { api, stamp -> loadSkills(api, stamp) }
    fun setSkillEnabled(expected: JsonObject, enabled: Boolean) = operation(mutating = true) { api, stamp ->
        val path = expected.string("path")
        require(File(path).isAbsolute) { "此技能没有可用于设置的本机路径" }
        require(state.value.skills.singleOrNull { it.string("path") == path } == expected) { "技能目录已变化，请刷新后重新确认" }
        api.setSkillEnabled(path, enabled)
        if (stamp != generation) return@operation
        loadSkills(api, stamp)
        if (stamp == generation) {
            val actual = state.value.skills.singleOrNull { it.string("path") == path }?.get("enabled")?.jsonPrimitive?.booleanOrNull
            mutable.update { it.copy(skillNotice = if (actual == enabled) "已重新扫描并确认技能${if (enabled) "开启" else "关闭"}" else "设置请求已返回，但扫描结果仍未变为所选状态；请核查管理来源或加载错误") }
        }
    }
    fun setExtraSkillRoots(text: String) = operation(mutating = true) { api, stamp ->
        val roots = OriginalSkillConfig.roots(text)
        withContext(Dispatchers.IO) { require(roots.all { File(it).isDirectory && File(it).canRead() }) { "存在无法读取的技能目录，请使用手机可访问的实际目录" } }
        api.setSkillRoots(roots)
        if (stamp != generation) return@operation
        mutable.update { it.copy(extraRoots = roots) }
        loadSkills(api, stamp)
        if (stamp == generation) mutable.update { it.copy(skillNotice = "核心已接收当前进程的额外目录，并重新扫描；重启核心后需重新设置") }
    }
    fun readSkill(skill: JsonObject) = operation { _, stamp ->
        val path = skill.string("path")
        require(state.value.skills.any { it.string("path") == path && it.string("name") == skill.string("name") }) { "技能目录已变化" }
        val contents = withContext(Dispatchers.IO) {
            val file = File(path); require(file.isFile && file.length() <= 512 * 1024) { "技能说明不可读或超过 512 KiB" }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
        }
        if (stamp == generation) mutable.update { it.copy(skillContents = contents, skillTitle = skill.string("name")) }
    }
    fun readPluginSkill(name: String) = operation { api, stamp ->
        val key = state.value.detailKey ?: error("请先打开插件详情")
        val plugin = state.value.plugins.singleOrNull { it.key == key } ?: error("插件目录已变化")
        val response = api.pluginSkill(plugin, name).jsonObject
        val contents = response["contents"]?.jsonPrimitive?.contentOrNull ?: error("服务未提供该技能正文")
        if (stamp == generation && state.value.detailKey == key) mutable.update { it.copy(skillContents = contents, skillTitle = name) }
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
    private suspend fun writeAndReload(api: OriginalCodexClient, stamp: Long, snapshot: OriginalConfigSnapshot, edit: JsonObject) {
        // 冲突/超时不重试，不从新版本自动覆盖；失败由 operation 显示并要求刷新。
        writeAndReload(api, stamp, snapshot, listOf(edit))
    }
    private suspend fun writeAndReload(api: OriginalCodexClient, stamp: Long, snapshot: OriginalConfigSnapshot, edits: List<JsonObject>) {
        val result = api.writeConfig(snapshot, edits).jsonObject
        if (stamp != generation) return
        val warning = when (result.string("status")) {
            "ok" -> null
            "okOverridden" -> (result["overriddenMetadata"] as? JsonObject)?.string("message").orEmpty().ifBlank { "配置已保存，但有效值由更高优先级配置覆盖" }
            else -> "写入状态尚不能识别，请核查实际配置"
        }
        load(api, stamp, false)
        if (stamp == generation) mutable.update { it.copy(error = warning) }
    }
    fun togglePlugin(plugin: OriginalPlugin) = operation(mutating = true) { api, stamp ->
        val snapshot = state.value.configSnapshot ?: error("缺少用户配置版本，请刷新")
        val installed = OriginalExtensions.catalog(api.installedPlugins(cwd).jsonObject).plugins
        val current = installed.singleOrNull { it.key == plugin.key && it.installed } ?: error("插件安装身份已变化，请刷新")
        require(current.enabled == plugin.enabled) { "插件启用状态已变化，请刷新" }
        require(current.summary.string("availability") == "AVAILABLE") { "当前插件可用策略不允许修改" }
        writeAndReload(api, stamp, snapshot, OriginalConfigEdits.plugin(current.id, !current.enabled))
    }
    fun changeHook(approved: JsonObject, trust: Boolean, revoke: Boolean = false) = operation(mutating = true) { api, stamp ->
        val snapshot = state.value.configSnapshot ?: error("缺少用户配置版本，请刷新")
        val groups = (api.hooks(cwd, null).jsonObject["data"] as? JsonArray).orEmpty()
        val matches = groups.mapNotNull { it as? JsonObject }.flatMap { (it["hooks"] as? JsonArray).orEmpty().mapNotNull { hook -> hook as? JsonObject } }
            .filter { it.string("key") == approved.string("key") }
        val current = matches.singleOrNull() ?: error("规则身份缺失或不唯一，请刷新")
        if (revoke) writeAndReload(api, stamp, snapshot, OriginalConfigEdits.revokeHook(approved, current))
        else writeAndReload(api, stamp, snapshot, OriginalConfigEdits.hook(approved, current, trust))
        if (revoke && stamp == generation) mutable.update { it.copy(status = "请核查刷新后的规则状态；其他配置来源仍可能保留信任或覆盖停用设置") }
    }
    fun editRules() { val snapshot = state.value.configSnapshot ?: return; if (!state.value.busy) mutable.update { it.copy(rulesEditor = OriginalHookDraft(snapshot, it.userHooks), error = null) } }
    fun closeRules() { if (!state.value.mutating) mutable.update { it.copy(rulesEditor = null) } }
    fun saveRules(draft: OriginalHookDraft, rules: JsonObject) = operation(mutating = true) { api, stamp ->
        require(state.value.rulesEditor == draft && state.value.configSnapshot == draft.snapshot) { "配置已变化，请重新打开规则编辑" }
        val edits = OriginalConfigEdits.hookRules(draft.hooks, rules)
        if (edits.isEmpty()) { mutable.update { it.copy(rulesEditor = null) }; return@operation }
        val result = api.writeConfig(draft.snapshot, edits).jsonObject
        if (stamp != generation) return@operation
        load(api, stamp, false)
        if (stamp == generation && result.string("status") in setOf("ok", "okOverridden")) mutable.update { it.copy(rulesEditor = null, status = "用户规则已保存，请核查实际加载与信任状态", error = if (result.string("status") == "okOverridden") "规则可能被其他配置覆盖，请核查来源" else it.error) }
        else if (stamp == generation) mutable.update { it.copy(error = "规则写入结果未知，请刷新核查") }
    }
    fun recordHook(run: JsonObject) {
        val item = OriginalExtensions.hookRun(run)
        if (item.id.isBlank()) return
        mutable.update { it.copy(hookRuns = OriginalThreadView.upsert(it.hookRuns, item).takeLast(200)) }
    }
}
