package cn.codex.core.original

import org.junit.Test
import kotlin.test.*

/** 按当前阶段要求仅提交测试源码，未执行。 */
class OriginalRuntimeLifecycleTest {
    private fun selected(): Pair<OriginalRuntimeLifecycle, Long> {
        val lifecycle = OriginalRuntimeLifecycle(); val epoch = lifecycle.beginConnection()
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadSelected("thread", null))
        return lifecycle to epoch
    }
    @Test fun staleEpochIsIgnored() {
        val (lifecycle, old) = selected()
        val current = lifecycle.beginConnection()
        lifecycle.accept(current, OriginalLifecycleEvent.ThreadSelected("new-thread", "new-turn"))
        val before = lifecycle.state
        lifecycle.accept(old, OriginalLifecycleEvent.TurnCompleted("thread", "old-turn"))
        lifecycle.disconnect(old)
        assertEquals(before, lifecycle.state)
    }
    @Test fun completedBeforeStartResponseStaysIdle() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested("request"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("thread", "A"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "A"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartResponded("request", "thread", "A"))
        assertNull(lifecycle.state.turnId); assertNull(lifecycle.state.pendingStartRequestId)
        assertTrue(lifecycle.isCompleted(epoch, "thread", "A"))
    }
    @Test fun completionOfADoesNotClearB() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("thread", "B"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.StopRequested("thread", "B"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "A"))
        assertEquals("B", lifecycle.state.turnId); assertEquals("B", lifecycle.state.stoppingTurnId)
    }
    @Test fun interruptAcknowledgementIsNotCompletion() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("thread", "A"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.StopRequested("thread", "A"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.InterruptAccepted("thread", "A"))
        assertEquals("A", lifecycle.state.turnId); assertEquals("A", lifecycle.state.stoppingTurnId)
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "A"))
        assertNull(lifecycle.state.turnId); assertNull(lifecycle.state.stoppingTurnId)
    }
    @Test fun disconnectClearsPendingAndRejectsLateFramesEvenAtSameEpoch() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested("r"))
        lifecycle.disconnect(epoch)
        val before = lifecycle.state
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartResponded("r", "thread", "late"))
        assertEquals(before, lifecycle.state); assertFalse(lifecycle.state.connected)
        assertNull(lifecycle.state.pendingStartRequestId)
    }
    @Test fun resumeDoesNotCreatePendingStartAndWrongRequestCannotTakeOwnership() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadSelected("thread", "resumed"))
        assertEquals("resumed", lifecycle.state.turnId); assertNull(lifecycle.state.pendingStartRequestId)
        assertFailsWith<IllegalStateException> { lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested("double")) }
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "resumed"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartRequested("right"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.StartResponded("wrong", "thread", "foreign"))
        assertNull(lifecycle.state.turnId); assertEquals("right", lifecycle.state.pendingStartRequestId)
    }
    @Test fun differentThreadCannotModifyCurrentTurnAndCompletedIdsAreBounded() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("other", "foreign"))
        assertNull(lifecycle.state.turnId)
        repeat(140) { lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "t$it")) }
        assertEquals(128, lifecycle.state.completedTurnIds.size)
        assertTrue("t139" in lifecycle.state.completedTurnIds); assertFalse("t0" in lifecycle.state.completedTurnIds)
    }
    @Test fun selectingSameThreadPreservesCompletedIdsAgainstLateStartedEvent() {
        val (lifecycle, epoch) = selected()
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnCompleted("thread", "old"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadSelected("thread", null))
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("thread", "old"))
        assertNull(lifecycle.state.turnId)
    }
}
