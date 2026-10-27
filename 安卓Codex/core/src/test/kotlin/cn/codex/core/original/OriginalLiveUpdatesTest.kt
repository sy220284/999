package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 行为测试源码；本阶段不运行编译型测试。 */
class OriginalLiveUpdatesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun legacyCommandRetainsArgumentBoundariesAndUsesLegacyDecision() {
        val params = obj("""{"conversationId":"t","callId":"c","command":["sh","-c","echo a b"],"cwd":"/data/user/0/app","parsedCmd":[]}""")
        val preview = OriginalLegacyApproval.preview("execCommandApproval", params)
        assertTrue(preview.reviewable)
        assertTrue(preview.body.contains("[2] \"echo a b\""))
        assertEquals(obj("""{"decision":"approved"}"""), OriginalLegacyApproval.decision(true))
        assertEquals(obj("""{"decision":{"denied":{"rejection":"用户拒绝此次操作"}}}"""), OriginalLegacyApproval.decision(false))
    }
    @Test fun legacyPatchShowsDeletedContentMovesAndSessionScope() {
        val params = obj("""{"conversationId":"t","callId":"c","grantRoot":"/project","fileChanges":{"/project/a":{"type":"delete","content":"secret line"},"/project/b":{"type":"update","unified_diff":"-old\n+new","move_path":"/project/c"}}}""")
        val preview = OriginalLegacyApproval.preview("applyPatchApproval", params)
        assertTrue(preview.reviewable)
        assertTrue(preview.body.contains("secret line")); assertTrue(preview.body.contains("/project/c"))
        assertTrue(preview.body.contains("会话")); assertTrue(preview.body.contains("/project"))
    }
    @Test fun unknownMalformedAndOversizedLegacyActionsCannotBeApproved() {
        assertFalse(OriginalLegacyApproval.preview("execCommandApproval", obj("""{"conversationId":"t","callId":"c","command":[true],"cwd":"/p"}""")).reviewable)
        assertFalse(OriginalLegacyApproval.preview("applyPatchApproval", obj("""{"conversationId":"t","callId":"c","fileChanges":{"a":{"type":"other"}}}""")).reviewable)
        val huge = buildJsonObject { put("conversationId", "t"); put("callId", "c"); put("cwd", "/p"); put("command", buildJsonArray { add("x".repeat(100_001)) }) }
        assertFalse(OriginalLegacyApproval.preview("execCommandApproval", huge).reviewable)
    }
    @Test fun updatesKeepTurnsSeparateAndPreserveUnknownPlanStatus() {
        var state = OriginalLiveUpdates.reduce(emptyMap(), "turn/plan/updated", obj("""{"turnId":"new","plan":[{"step":"检查","status":"futureStatus"}],"explanation":"原因"}"""))
        state = OriginalLiveUpdates.reduce(state, "thread/tokenUsage/updated", obj("""{"turnId":"old","tokenUsage":{"last":{"totalTokens":9}}}"""))
        assertNotNull(state["new"]?.plan); assertNull(state["new"]?.usage)
        assertEquals(9, state["old"]?.usage?.get("last")?.jsonObject?.get("totalTokens")?.jsonPrimitive?.int)
        assertTrue(OriginalLiveUpdates.describe(state.getValue("new")).contains("futureStatus"))
        assertTrue(OriginalLiveUpdates.describe(state.getValue("old")).contains("未提供"))
    }
    @Test fun toolProgressIsBoundedAndDoesNotReplaceItemHistory() {
        var state = OriginalLiveUpdates.reduce(emptyMap(), "item/commandExecution/terminalInteraction", obj("""{"turnId":"t","itemId":"i","processId":"p","stdin":"ls\n"}"""))
        state = OriginalLiveUpdates.reduce(state, "item/mcpToolCall/progress", obj("""{"turnId":"t","itemId":"m","message":"扫描中"}"""))
        assertTrue(OriginalLiveUpdates.describe(state.getValue("t")).contains("ls"))
        assertTrue(OriginalLiveUpdates.describe(state.getValue("t")).contains("扫描中"))
        repeat(40) { i -> state = OriginalLiveUpdates.reduce(state, "turn/plan/updated", obj("""{"turnId":"$i","plan":[]}""")) }
        assertEquals(16, state.size)
        assertEquals(state, OriginalLiveUpdates.reduce(state, "turn/plan/updated", obj("""{"turnId":"","plan":[]}""")))
    }
    @Test fun oversizedSupplementalTextIsExplicitlyMarkedWithoutBreakingPlanStructure() {
        val params = buildJsonObject { put("turnId", "t"); put("plan", buildJsonArray {
            add(buildJsonObject { put("step", "x".repeat(70_000)); put("status", "pending") })
        }) }
        var state = OriginalLiveUpdates.reduce(emptyMap(), "turn/plan/updated", params)
        assertTrue(OriginalLiveUpdates.describe(state.getValue("t")).contains("仅展示部分内容"))
        state = OriginalLiveUpdates.reduce(state, "item/fileChange/outputDelta", buildJsonObject {
            put("turnId", "t"); put("itemId", "i"); put("delta", "x".repeat(70_000))
        })
        assertTrue(state.getValue("t").tools.getValue("i").length <= 32_768)
        assertTrue(OriginalLiveUpdates.describe(state.getValue("t")).contains("仅保留最近"))
    }

}
