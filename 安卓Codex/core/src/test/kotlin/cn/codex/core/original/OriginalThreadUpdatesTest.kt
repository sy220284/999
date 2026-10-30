package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 本阶段仅写入行为测试源码，不运行编译型测试。 */
class OriginalThreadUpdatesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun backgroundStartDoesNotSelectOrReplaceTheCurrentThread() {
        val updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/started", obj("""{"thread":{"id":"other","name":"后台会话","turns":[]}}"""))
        assertEquals("other", updates.started?.get("id")?.jsonPrimitive?.content)
        assertEquals(setOf("other"), updates.threads.keys)
    }
    @Test fun lateListResponseCannotRestoreDeletedRowsOrOldNames() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/name/updated", obj("""{"threadId":"a","threadName":"新名称"}"""))
        updates = OriginalThreadUpdates.reduce(updates, "thread/deleted", obj("""{"threadId":"b"}"""))
        val rows = listOf(obj("""{"id":"a","name":"旧名称"}"""), obj("""{"id":"b"}"""))
        val merged = OriginalThreadUpdates.overlay(rows, updates, false, 0)
        assertEquals(listOf("a"), merged.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("新名称", merged.single().getValue("name").jsonPrimitive.content)
        assertEquals("旧名称", OriginalThreadUpdates.overlay(rows, updates, false, updates.version).first().getValue("name").jsonPrimitive.content)
    }
    @Test fun archiveRestoreAndCloseHaveDifferentEffects() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/archived", obj("""{"threadId":"a"}"""))
        val rows = listOf(obj("""{"id":"a"}"""))
        assertTrue(OriginalThreadUpdates.overlay(rows, updates, false, 0).isEmpty())
        assertFalse(OriginalThreadUpdates.canUse(updates.threads["a"]))
        updates = OriginalThreadUpdates.reduce(updates, "thread/unarchived", obj("""{"threadId":"a"}"""))
        assertTrue(OriginalThreadUpdates.canUse(updates.threads["a"]))
        updates = OriginalThreadUpdates.reduce(updates, "thread/closed", obj("""{"threadId":"a"}"""))
        assertEquals(1, OriginalThreadUpdates.overlay(rows, updates, false, 0).size)
        assertFalse(OriginalThreadUpdates.canUse(updates.threads["a"]))
    }
    @Test fun reloadOnlyAcknowledgesChangesObservedBeforeItStarted() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/reverted", obj("""{"threadId":"a"}"""))
        val stamp = updates.version
        updates = OriginalThreadUpdates.reduce(updates, "thread/reverted", obj("""{"threadId":"a"}"""))
        assertTrue(OriginalThreadUpdates.acknowledgeHistory(updates, "a", stamp).threads.getValue("a").historyVersion > 0)
        assertEquals(0L, OriginalThreadUpdates.acknowledgeHistory(updates, "a", updates.version).threads.getValue("a").historyVersion)
    }
    @Test fun attachmentEventDoesNotInventPayloadAndUnknownReadStateIsNotRead() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/attachment/updated", obj("""{"threadId":"a","attachmentId":"x","operation":"created","attachmentType":"file","identityKey":"key"}"""))
        assertTrue(updates.threads.getValue("a").attachmentVersion > 0)
        assertEquals("状态未同步", OriginalThreadUpdates.readLabel(updates.threads["a"]))
        updates = OriginalThreadUpdates.reduce(updates, "thread/readState/changed", obj("""{"threadId":"a","readState":{"firstUnread":null,"revision":"r"}}"""))
        assertEquals("已读", OriginalThreadUpdates.readLabel(updates.threads["a"]))
    }
    @Test fun emptyNameClearsTitleAndEnvironmentSettingsRemainThreadScoped() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/name/updated", obj("""{"threadId":"a"}"""))
        assertEquals(JsonNull, updates.threads.getValue("a").patch["name"])
        updates = OriginalThreadUpdates.reduce(updates, "thread/environment/connected", obj("""{"threadId":"a","environmentId":"env"}"""))
        updates = OriginalThreadUpdates.reduce(updates, "thread/settings/updated", obj("""{"threadId":"b","threadSettings":{"model":"m","modelProvider":"p","cwd":"/p"}}"""))
        assertEquals("env", updates.threads.getValue("a").environmentId)
        assertNull(updates.threads.getValue("a").settings)
        assertNull(updates.threads.getValue("b").environmentId)
    }
    @Test fun unrelatedNewReadStateDoesNotReapplyAnOldNameOverFreshListData() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/name/updated", obj("""{"threadId":"a","threadName":"旧通知标题"}"""))
        val stamp = updates.version
        updates = OriginalThreadUpdates.reduce(updates, "thread/readState/changed", obj("""{"threadId":"a","readState":{"firstUnread":null,"revision":"r"}}"""))
        val rows = listOf(obj("""{"id":"a","name":"新快照标题"}"""))
        assertEquals("新快照标题", OriginalThreadUpdates.overlay(rows, updates, false, stamp).single().getValue("name").jsonPrimitive.content)
    }

    @Test fun terminalLifecycleClearsOnlyTheMatchingThreadAndCannotResurrectItsOldTurn() {
        val lifecycle = OriginalRuntimeLifecycle(); val epoch = lifecycle.beginConnection()
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadSelected("a", "turn"))
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadUnavailable("other"))
        assertEquals("turn", lifecycle.state.turnId)
        lifecycle.accept(epoch, OriginalLifecycleEvent.ThreadUnavailable("a"))
        assertEquals("a", lifecycle.state.threadId); assertNull(lifecycle.state.turnId)
        lifecycle.accept(epoch, OriginalLifecycleEvent.TurnStarted("a", "turn"))
        assertNull(lifecycle.state.turnId)
    }

    @Test fun unrelatedReadEventDoesNotPreventAcknowledgingAnObservedClose() {
        var updates = OriginalThreadUpdates.reduce(OriginalThreadUpdatesState(), "thread/closed", obj("""{"threadId":"a"}"""))
        val stamp = updates.version
        updates = OriginalThreadUpdates.reduce(updates, "thread/readState/changed", obj("""{"threadId":"a","readState":{"firstUnread":null,"revision":"r"}}"""))
        val reloaded = OriginalThreadUpdates.acknowledgeHistory(updates, "a", stamp).threads.getValue("a")
        assertEquals("available", reloaded.availability)
        assertEquals("会话内容已重新载入", reloaded.notice)
    }

}
