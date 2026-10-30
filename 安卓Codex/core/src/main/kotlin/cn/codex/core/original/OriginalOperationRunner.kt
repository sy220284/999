package cn.codex.core.original

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OriginalOperationStatus(val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val notice: String = "尚未读取", val error: String? = null)

/** 管理操作共用连接代次、前台记录和错误处理；业务请求由各控制器明确选择。 */
class OriginalOperationRunner(private val scope: CoroutineScope, private val activeTask: () -> Boolean,
    private val lifecycle: OriginalMutationLifecycle) {
    private val mutable = MutableStateFlow(OriginalOperationStatus())
    val state = mutable.asStateFlow()
    private var api: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    fun bind(client: OriginalCodexClient?) { generation++; job?.cancel(); api = client; mutable.value = OriginalOperationStatus(connected = client != null) }
    fun current(stamp: Long) = stamp == generation
    fun notice(stamp: Long, text: String) { if (current(stamp)) mutable.update { it.copy(notice = text) } }
    fun run(mutating: Boolean = false, action: suspend (OriginalCodexClient, Long) -> Unit) {
        if (state.value.busy) return
        val client = api ?: run { mutable.update { it.copy(error = "原版核心未连接，请返回启动") }; return }
        if (mutating && activeTask()) { mutable.update { it.copy(error = "请先结束其他任务或变更") }; return }
        val stamp = generation
        val ticket = if (mutating) try { lifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "无法托管操作") }; return } else null
        mutable.update { it.copy(busy = true, mutating = mutating, error = null, notice = "正在处理") }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var confirmed = false
            try { action(client, stamp); confirmed = current(stamp) }
            catch (failure: CancellationException) {
                if (failure !is TimeoutCancellationException) throw failure
                if (current(stamp)) mutable.update { it.copy(error = "操作超时，请核查实际状态；不会自动重发") }
            } catch (failure: Exception) { if (current(stamp)) mutable.update { it.copy(error = failure.message ?: "操作失败，请核查实际状态") } }
            finally {
                try { if (ticket != null) lifecycle.finish(ticket, confirmed && current(stamp)) }
                catch (failure: Exception) { if (current(stamp)) mutable.update { it.copy(error = "操作记录保存失败，请核查实际状态") } }
                finally { if (current(stamp)) mutable.update { it.copy(busy = false, mutating = false) } }
            }
        }
    }
}
