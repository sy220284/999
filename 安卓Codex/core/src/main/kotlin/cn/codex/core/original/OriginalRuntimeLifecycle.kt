package cn.codex.core.original

/** 单一调度上下文消费的纯状态机；不执行 RPC，不重放用户任务。 */
data class OriginalLifecycleState(
    val connectionEpoch: Long = 0, val connected: Boolean = false,
    val threadId: String? = null, val turnId: String? = null,
    val pendingStartRequestId: String? = null, val stoppingTurnId: String? = null,
    val completedTurnIds: Set<String> = emptySet()
)
sealed interface OriginalLifecycleEvent {
    data class ThreadSelected(val threadId: String, val activeTurnId: String?) : OriginalLifecycleEvent
    data class StartRequested(val requestId: String) : OriginalLifecycleEvent
    data class StartResponded(val requestId: String, val threadId: String, val turnId: String) : OriginalLifecycleEvent
    data class TurnStarted(val threadId: String, val turnId: String) : OriginalLifecycleEvent
    data class TurnCompleted(val threadId: String, val turnId: String) : OriginalLifecycleEvent
    data class StopRequested(val threadId: String, val turnId: String) : OriginalLifecycleEvent
    data class InterruptAccepted(val threadId: String, val turnId: String) : OriginalLifecycleEvent
    data class ThreadUnavailable(val threadId: String) : OriginalLifecycleEvent
    data object ThreadCleared : OriginalLifecycleEvent
    data object Disconnected : OriginalLifecycleEvent
}
class OriginalRuntimeLifecycle {
    var state = OriginalLifecycleState()
        private set
    fun beginConnection(): Long {
        state = OriginalLifecycleState(connectionEpoch = state.connectionEpoch + 1, connected = true)
        return state.connectionEpoch
    }
    fun isCurrent(epoch: Long) = state.connected && state.connectionEpoch == epoch
    fun isCompleted(epoch: Long, threadId: String, turnId: String) =
        isCurrent(epoch) && state.threadId == threadId && turnId in state.completedTurnIds
    fun disconnect(epoch: Long) = accept(epoch, OriginalLifecycleEvent.Disconnected)
    fun accept(epoch: Long, event: OriginalLifecycleEvent): OriginalLifecycleState {
        if (!isCurrent(epoch)) return state
        val old = state
        state = when (event) {
            is OriginalLifecycleEvent.ThreadUnavailable -> if (old.threadId == event.threadId)
                old.copy(turnId = null, pendingStartRequestId = null, stoppingTurnId = null,
                    completedTurnIds = (old.completedTurnIds + listOfNotNull(old.turnId)).toList().takeLast(128).toSet()) else old
            OriginalLifecycleEvent.ThreadCleared -> {
                check(old.turnId == null && old.pendingStartRequestId == null) { "活动任务尚未结束" }
                old.copy(threadId = null, turnId = null, pendingStartRequestId = null, stoppingTurnId = null, completedTurnIds = emptySet())
            }
            is OriginalLifecycleEvent.ThreadSelected -> {
                require(event.threadId.isNotBlank())
                check(old.turnId == null && old.pendingStartRequestId == null || old.threadId == event.threadId) { "活动任务尚未结束，不能切换线程" }
                if (old.threadId == event.threadId && (old.turnId != null || old.pendingStartRequestId != null)) old
                else old.copy(threadId = event.threadId, turnId = event.activeTurnId?.takeIf { it.isNotBlank() }?.takeUnless { old.threadId == event.threadId && it in old.completedTurnIds }, pendingStartRequestId = null, stoppingTurnId = null, completedTurnIds = if (old.threadId == event.threadId) old.completedTurnIds else emptySet())
            }
            is OriginalLifecycleEvent.StartRequested -> {
                require(event.requestId.isNotBlank())
                check(old.threadId != null && old.turnId == null && old.pendingStartRequestId == null) { "已有活动任务或正在等待启动确认" }
                old.copy(pendingStartRequestId = event.requestId)
            }
            is OriginalLifecycleEvent.StartResponded -> {
                if (event.threadId != old.threadId || event.requestId != old.pendingStartRequestId || event.turnId.isBlank()) old
                else old.copy(pendingStartRequestId = null, turnId = when {
                    event.turnId in old.completedTurnIds -> old.turnId?.takeUnless { it == event.turnId }
                    old.turnId == null || old.turnId == event.turnId -> event.turnId
                    else -> old.turnId
                })
            }
            is OriginalLifecycleEvent.TurnStarted -> {
                if (event.threadId != old.threadId || event.turnId.isBlank() || event.turnId in old.completedTurnIds || old.turnId != null && old.turnId != event.turnId) old
                else old.copy(turnId = event.turnId)
            }
            is OriginalLifecycleEvent.TurnCompleted -> {
                if (event.threadId != old.threadId || event.turnId.isBlank()) old
                else old.copy(turnId = old.turnId?.takeUnless { it == event.turnId }, stoppingTurnId = old.stoppingTurnId?.takeUnless { it == event.turnId },
                    completedTurnIds = (old.completedTurnIds + event.turnId).toList().takeLast(128).toSet())
            }
            is OriginalLifecycleEvent.StopRequested -> if (event.threadId == old.threadId && event.turnId == old.turnId) old.copy(stoppingTurnId = event.turnId) else old
            is OriginalLifecycleEvent.InterruptAccepted -> old // ACK 只确认接收中断，不能代替完成通知。
            OriginalLifecycleEvent.Disconnected -> OriginalLifecycleState(connectionEpoch = old.connectionEpoch)
        }
        return state
    }
}
