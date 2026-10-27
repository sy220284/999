package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 测试源码；本阶段不编译或执行。 */
class OriginalRemainingContractsTest {
    @Test fun privateSharingRequiresExplicitRecipientsAndValidRoles() {
        val targets = OriginalRemainingContracts.targets("user\tuser-id\treader\nworkspace\tworkspace-id\teditor")
        assertEquals(2, targets.size)
        assertEquals("workspace", targets[1].jsonObject.getValue("principalType").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.targets("user\tid\towner") }
    }
    @Test fun guardianEventUsesTheOriginalActionShapeAndStdinIdentity() {
        val notification = buildJsonObject {
            put("reviewId", "review"); put("threadId", "thread"); put("turnId", "turn"); put("startedAtMs", 1); put("completedAtMs", 2)
            put("review", buildJsonObject { put("status", "denied"); put("rationale", "需要人工确认") })
            put("action", buildJsonObject { put("type", "writeStdin"); put("approvalId", "approval"); put("processId", "process"); put("stdin", "y\n"); put("cwd", "/project") })
        }
        val event = OriginalRemainingContracts.guardianEvent(notification)
        assertEquals("review", event.getValue("id").jsonPrimitive.content)
        val action = event.getValue("action").jsonObject
        assertEquals("write_stdin", action.getValue("type").jsonPrimitive.content)
        assertEquals("process", action.getValue("process_id").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.guardianEvent(JsonObject(notification + ("review" to buildJsonObject { put("status", "approved") }))) }
    }
    @Test fun guardianPermissionsUseCoreFieldNamesWithoutWideningPaths() {
        val event=buildJsonObject {
            put("reviewId","permission");put("threadId","thread");put("turnId","turn")
            put("review",buildJsonObject { put("status","denied") })
            put("action",buildJsonObject {
                put("type","requestPermissions");put("reason",JsonNull)
                put("permissions",buildJsonObject { put("network",JsonNull);put("fileSystem",buildJsonObject { put("read",buildJsonArray { add("/project/read") });put("write",JsonNull);put("globScanMaxDepth",2) }) })
            })
        }
        val permissions=OriginalRemainingContracts.guardianEvent(event).getValue("action").jsonObject.getValue("permissions").jsonObject
        assertFalse(permissions.containsKey("fileSystem"))
        val file=permissions.getValue("file_system").jsonObject
        assertEquals(JsonPrimitive(2),file["glob_scan_max_depth"])
        assertEquals("/project/read",file.getValue("entries").jsonArray.single().jsonObject.getValue("path").jsonObject.getValue("path").jsonPrimitive.content)
    }
    @Test fun injectedHistoryKeepsArrayItemsButRejectsAnEmptyOrWrongRoot() {
        val content = "[{\"type\":\"message\",\"role\":\"user\",\"content\":\"补充历史\"}]"
        assertEquals(1, OriginalRemainingContracts.history(content).size)
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.history("{}") }
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.history("[]") }
    }
    @Test fun feedbackTagsAreStringsAndHistoryRecordsPreserveFailures() {
        assertEquals("mobile", OriginalRemainingContracts.tags("{\"platform\":\"mobile\"}").getValue("platform").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.tags("{\"token\":42}") }
        assertFailsWith<IllegalArgumentException> { OriginalRemainingContracts.historyResults("[]") }
    }
}
