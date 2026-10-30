package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class PermissionSelectionTest {
    private fun request() = PermissionSelection(Json.parseToJsonElement("""{"network":{"enabled":true},"fileSystem":{"read":["/read"],"write":["/write"],"entries":[{"path":{"type":"glob_pattern","pattern":"/project/*.kt"},"access":"read"}],"globScanMaxDepth":3}}""").jsonObject)
    @Test fun onlyExplicitlySelectedReadPathIsGranted() {
        val response = request().response(setOf("read:0"), false, false)
        val grants = response.getValue("permissions").jsonObject
        assertFalse(grants.containsKey("network"))
        assertEquals(JsonNull, grants.getValue("fileSystem").jsonObject["write"])
        assertEquals("turn", response.getValue("scope").jsonPrimitive.content)
        assertTrue(response.getValue("strictAutoReview").jsonPrimitive.boolean)
    }
    @Test fun entryAndSessionScopePreserveExactRequestWithoutWidening() {
        val response = request().response(setOf("entries:0"), true, true)
        val files = response.getValue("permissions").jsonObject.getValue("fileSystem").jsonObject
        assertEquals("read", files.getValue("entries").jsonArray.single().jsonObject.getValue("access").jsonPrimitive.content)
        assertEquals(3, files.getValue("globScanMaxDepth").jsonPrimitive.int)
        assertEquals("session", response.getValue("scope").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { request().response(setOf("write:/unknown"), false, false) }
    }
}
