package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class OriginalSettingsSection(val title: String) {
    AccountUsage("账号用量"), ThreadUsage("当前会话用量"), Messages("工作区消息"), Provider("服务能力"),
    Requirements("管理要求"), Profiles("权限方案"), Features("实验功能")
}
data class OriginalSettingsState(
    val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val section: OriginalSettingsSection = OriginalSettingsSection.AccountUsage,
    val usage: JsonObject? = null, val usageThread: String? = null,
    val messages: JsonObject? = null, val provider: JsonObject? = null, val requirements: JsonObject? = null,
    val profiles: List<JsonObject> = emptyList(), val features: List<JsonObject> = emptyList(),
    val nextCursor: String? = null, val featureThread: String? = null,
    val error: String? = null, val notice: String = "请选择一项读取"
)

/** 专用设置目录；分页上下文固定，运行时开关以核心确认和重读为准。 */
class OriginalSettingsController(private val scope: CoroutineScope, private val cwd: String,
    private val activeTask: () -> Boolean, private val threadId: () -> String?,
    private val lifecycle: OriginalMutationLifecycle = OriginalMutationLifecycle.None) {
    private val mutable = MutableStateFlow(OriginalSettingsState())
    val state = mutable.asStateFlow()
    private var client: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    private val cursors = mutableSetOf<String>()
    companion object {
        // 固定上传版本 config_processor.rs 的 SUPPORTED_EXPERIMENTAL_FEATURE_ENABLEMENT。
        val runtimeFeatures = setOf("api_key_cyber_access_programs", "api_key_model_discovery", "auth_elicitation",
            "background_paginated_rollout_migration", "codex_apps_mcp_2026_07_28", "mcp_2026_07_28", "memories",
            "mentions_v2", "remote_control", "remote_plugin", "tool_suggest")
        fun canToggle(feature: JsonObject) = feature.string("name") in runtimeFeatures && feature.string("stage") !in setOf("removed", "deprecated")
    }
    fun bind(api: OriginalCodexClient?) {
        generation++; job?.cancel(); client = api; cursors.clear()
        mutable.value = OriginalSettingsState(connected = api != null)
    }
    private fun operation(mutating: Boolean = false, action: suspend (OriginalCodexClient, Long) -> Unit) {
        if (state.value.busy) return
        val api = client ?: return
        if (mutating && activeTask()) { mutable.update { it.copy(error = "请先结束当前任务或其他变更") }; return }
        val stamp = generation
        val ticket = if (mutating) try { lifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = "无法托管实验设置变更：${failure.message.orEmpty()}") }; return } else null
        mutable.update { it.copy(busy = true, mutating = mutating, error = null, notice = "正在读取或处理") }
        job = scope.launch {
            var confirmed = false
            try { action(api, stamp); confirmed = stamp == generation && state.value.error == null }
            catch (failure: CancellationException) {
                if (failure !is TimeoutCancellationException) throw failure
                if (stamp == generation) mutable.update { it.copy(error = "设置请求超时，请刷新核查实际状态；不会自动重试") }
            } catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = failure.message ?: "设置请求失败") } }
            finally {
                try { if (ticket != null) lifecycle.finish(ticket, confirmed && stamp == generation) }
                catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = "操作记录未能保存，请核查实际状态") } }
                finally { if (stamp == generation) mutable.update { it.copy(busy = false, mutating = false) } }
            }
        }
    }
    fun refresh(section: OriginalSettingsSection = state.value.section) {
        if (state.value.busy || client == null) return
        val selectedThread = threadId()
        cursors.clear()
        mutable.update { it.copy(section = section, nextCursor = null, usage = null, messages = null, provider = null, requirements = null, profiles = emptyList(), features = emptyList(), featureThread = selectedThread, usageThread = null) }
        operation { api, stamp ->
            when (section) {
                OriginalSettingsSection.AccountUsage, OriginalSettingsSection.ThreadUsage -> {
                    val thread = if (section == OriginalSettingsSection.ThreadUsage) selectedThread?.also { require(it.isNotBlank()) } ?: error("请先打开一个会话") else null
                    val result = api.accountUsage(thread).jsonObject
                    if (stamp == generation) mutable.update { it.copy(usage = result, usageThread = thread, notice = "已读取服务返回的用量") }
                }
                OriginalSettingsSection.Messages -> { val result = api.workspaceMessages().jsonObject; if (stamp == generation) mutable.update { it.copy(messages = result, notice = "已读取工作区消息") } }
                OriginalSettingsSection.Provider -> { val result = api.providerCapabilities().jsonObject; if (stamp == generation) mutable.update { it.copy(provider = result, notice = "已读取模型提供方能力声明") } }
                OriginalSettingsSection.Requirements -> { val result = api.configRequirements().jsonObject; if (stamp == generation) mutable.update { it.copy(requirements = result, notice = "已读取有效管理要求") } }
                OriginalSettingsSection.Profiles, OriginalSettingsSection.Features -> loadPage(api, stamp, null)
            }
        }
    }
    private suspend fun loadPage(api: OriginalCodexClient, stamp: Long, cursor: String?) {
        val section = state.value.section
        val result = when (section) {
            OriginalSettingsSection.Features -> api.experimentalFeatures(state.value.featureThread, cursor)
            OriginalSettingsSection.Profiles -> api.permissionProfiles(cwd, cursor)
            else -> return
        }.jsonObject
        val entries = result["data"]?.jsonArray?.map { it.jsonObject } ?: error("设置目录响应缺少 data")
        val key = if (section == OriginalSettingsSection.Features) "name" else "id"
        require(entries.all { it.string(key).isNotBlank() }) { "设置目录包含无身份条目" }
        val next = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        if (stamp != generation) return
        val repeated = next != null && (next == cursor || !cursors.add(next))
        mutable.update { previous ->
            val old = if (cursor == null) emptyList() else if (section == OriginalSettingsSection.Features) previous.features else previous.profiles
            val combined = (old + entries).associateBy { it.string(key) }.values.toList()
            previous.copy(features = if (section == OriginalSettingsSection.Features) combined else previous.features,
                profiles = if (section == OriginalSettingsSection.Profiles) combined else previous.profiles,
                nextCursor = next.takeUnless { repeated }, notice = "已读取 ${combined.size} 项",
                error = if (repeated) "服务重复返回分页游标，已停止继续加载" else null)
        }
    }
    fun more() {
        val cursor = state.value.nextCursor ?: return
        operation { api, stamp -> loadPage(api, stamp, cursor) }
    }
    fun toggle(expected: JsonObject, enabled: Boolean) {
        if (state.value.section != OriginalSettingsSection.Features || !canToggle(expected)) return
        val name = expected.string("name")
        if (state.value.features.singleOrNull { it.string("name") == name } != expected) { mutable.update { it.copy(error = "功能状态已变化，请刷新后重新确认") }; return }
        operation(mutating = true) { api, stamp ->
            val response = api.setExperimentalFeature(name, enabled).jsonObject
            require(response["enablement"]?.jsonObject?.get(name)?.jsonPrimitive?.booleanOrNull == enabled) { "核心未确认此开关已受理，请刷新核查；不会重复提交" }
            if (stamp != generation) return@operation
            cursors.clear()
            mutable.update { it.copy(nextCursor = null) }
            loadPage(api, stamp, null)
            if (stamp == generation) mutable.update { it.copy(notice = "核心已确认运行时设置并重新读取；只影响当前核心进程，有效状态还受管理要求与项目配置影响") }
        }
    }
}
