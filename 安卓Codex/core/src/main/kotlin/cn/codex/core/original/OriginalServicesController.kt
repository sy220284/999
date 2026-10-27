package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class OriginalCreditRequest(val key: String, val creditId: String)
interface OriginalCreditRequestStore {
    fun read(): OriginalCreditRequest?
    fun write(request: OriginalCreditRequest)
    fun clear()
}

data class OriginalServicesState(val gateway: JsonObject? = null, val gatewayUrl: String? = null, val gatewayEvents: Long = 0,
    val creditRequestKey: String = UUID.randomUUID().toString(), val creditRequestId: String? = null, val creditRecoveryError: String? = null, val creditOutcome: String? = null, val mailStatus: String? = null, val feedback: JsonObject? = null)
class OriginalServicesController(scope: CoroutineScope, private val threadId: () -> String?, activeTask: () -> Boolean, lifecycle: OriginalMutationLifecycle, private val creditStore: OriginalCreditRequestStore? = null) {
    val operations = OriginalOperationRunner(scope, activeTask, lifecycle)
    private val mutable = MutableStateFlow(OriginalServicesState())
    val state = mutable.asStateFlow()
    fun bind(api: OriginalCodexClient?) {
        operations.bind(api)
        val request = runCatching { creditStore?.read() }
        mutable.value = request.fold(onSuccess = { value -> OriginalServicesState(creditRequestKey = value?.key ?: UUID.randomUUID().toString(), creditRequestId = value?.creditId) }, onFailure = { OriginalServicesState(creditRecoveryError = "上次重置券请求记录读取失败：${it.message}。核查账户后再明确新建请求。") })
    }
    private fun update(stamp: Long, change: (OriginalServicesState) -> OriginalServicesState) { if (operations.current(stamp)) mutable.update(change) }
    fun gatewayChanged(params: JsonObject) { mutable.update { it.copy(gateway = JsonObject((it.gateway.takeIf { old -> old?.string("providerId") == params.string("providerId") }.orEmpty()) + params), gatewayUrl = params.string("authUrl").ifBlank { null }, gatewayEvents = it.gatewayEvents + 1) } }
    private suspend fun readGateway(api: OriginalCodexClient, stamp: Long) {
        val revision = state.value.gatewayEvents; val result = api.gatewayStatus().jsonObject
        update(stamp) { if (it.gatewayEvents == revision) it.copy(gateway = result) else it }
    }
    fun gatewayStatus() = operations.run { api, stamp -> readGateway(api, stamp); operations.notice(stamp, "已读取网关授权状态") }
    fun loginGateway() = operations.run(true) { api, stamp -> api.loginGateway(); readGateway(api, stamp); operations.notice(stamp, "已申请网关授权，请按授权页面完成；完成状态由服务返回") }
    fun cancelGateway() = operations.run(true) { api, stamp -> api.cancelGateway(); update(stamp) { it.copy(gatewayUrl = null) }; readGateway(api, stamp); operations.notice(stamp, "已请求取消网关授权") }
    fun newCreditRequest() { if (!operations.state.value.busy) { creditStore?.clear(); mutable.update { it.copy(creditRequestKey = UUID.randomUUID().toString(), creditRequestId = null, creditOutcome = null, creditRecoveryError = null) } } }
    fun consumeCredit(creditId: String) {
        val key = state.value.creditRequestKey
        operations.run(true) { api, stamp ->
            check(state.value.creditRecoveryError == null) { "请先处理上次重置券请求记录" }
            val selected = state.value.creditRequestId ?: creditId
            withContext(Dispatchers.IO) { creditStore?.write(OriginalCreditRequest(key, selected)) }
            update(stamp) { it.copy(creditRequestId = selected) }
            val result = api.consumeResetCredit(key, selected.takeIf { it.isNotBlank() }).jsonObject
            update(stamp) { it.copy(creditOutcome = result.string("outcome")) }; operations.notice(stamp, "服务已返回重置券使用结果")
        }
    }
    fun sendCreditEmail(type: String) = operations.run(true) { api, stamp ->
        require(type in setOf("credits", "usage_limit")); val response = api.nudgeCredits(type).jsonObject
        update(stamp) { it.copy(mailStatus = response.string("status")) }; operations.notice(stamp, "服务已返回提醒邮件发送状态")
    }
    fun feedback(upload: Boolean, path: String, classification: String, reason: String, includeThread: Boolean, logs: Boolean, files: String, tags: String) {
        val thread = if (includeThread) threadId() else null
        operations.run(true) { api, stamp ->
            require(classification.isNotBlank()) { "请填写反馈分类" }
            val extra = files.lineSequence().filter { it.isNotBlank() }.toList()
            require(logs || extra.isEmpty()) { "提交额外日志前需要明确开启包含日志" }
            val labels = OriginalRemainingContracts.tags(tags)
            val result = if (upload) api.uploadFeedback(classification, reason.takeIf { it.isNotBlank() }, thread, logs, extra, labels)
                else { require(java.io.File(path).isAbsolute) { "本机保存需要绝对路径" }; api.saveFeedback(path, classification, reason.takeIf { it.isNotBlank() }, thread, logs, extra, labels) }
            update(stamp) { it.copy(feedback = result.jsonObject) }; operations.notice(stamp, if (upload) "反馈已上传，结果标识如下" else "反馈已保存到服务返回的本机路径")
        }
    }
}
