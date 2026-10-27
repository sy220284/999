package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 源码先行；本阶段不运行编译型测试。 */
class OriginalMcpConfigTest {
    @Test fun quotedServerNameRemainsOnePathSegmentAndDeletionUsesNull() {
        val edit = OriginalMcpConfig.edit("dev.\\\"server", null)
        assertEquals("mcp_servers.\"dev.\\\\\\\"server\"", edit.getValue("keyPath").jsonPrimitive.content)
        assertEquals(JsonNull, edit["value"])
        assertEquals("replace", edit.getValue("mergeStrategy").jsonPrimitive.content)
    }
    @Test fun switchingTransportRemovesOnlyOldTransportAndPreservesToolPolicy() {
        val original = buildJsonObject {
            put("command", "node"); put("args", buildJsonArray { add("server.js") }); put("cwd", "/project")
            put("enabled_tools", buildJsonArray { add("read") }); put("required", true)
        }
        val result = OriginalMcpConfig.form(original, "https://example.com/mcp", "", "", "", "", "", "", "", "", true, http = true)
        assertFalse(result.containsKey("command")); assertFalse(result.containsKey("args")); assertFalse(result.containsKey("cwd"))
        assertEquals(original["enabled_tools"], result["enabled_tools"]); assertEquals(original["required"], result["required"])
        assertEquals("https://example.com/mcp", result.getValue("url").jsonPrimitive.content)
    }
    @Test fun argumentsStayLiteralAndInvalidNumericFieldsDoNotBecomeDefaults() {
        val config = OriginalMcpConfig.form(buildJsonObject {}, "", "/system/bin/sh", "-c\necho $" + "HOME", "/project", "PATH\nLANG", "", "", "5", "20", false, http = false)
        assertEquals(listOf("-c", "echo $" + "HOME"), config.getValue("args").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(config.getValue("enabled").jsonPrimitive.boolean)
        val existing = buildJsonObject { put("command", "program"); put("args", buildJsonArray { add(""); add(" keep spaces "); add("") }) }
        val unchanged = OriginalMcpConfig.form(existing, "", "program", "\n keep spaces \n", "", "", "", "", "", "", true, false)
        assertEquals(existing["args"], unchanged["args"])
        assertFailsWith<IllegalArgumentException> { OriginalMcpConfig.form(buildJsonObject {}, "https://example.com", "", "", "", "", "", "", "abc", "", true, true) }
    }
    @Test fun effectiveProjectServerCannotBeDeletedAsUserOwned() {
        val response = buildJsonObject {
            put("config", buildJsonObject { put("mcp_servers", buildJsonObject { put("project", buildJsonObject { put("url", "https://example.com") }) }) })
            put("layers", buildJsonArray {})
        }
        val entry = OriginalMcpConfig.entries(response, emptyList()).single()
        assertNull(entry.userConfig)
        assertEquals("project", entry.name)
    }
    @Test fun sameTransportKeepsUneditedSecretsAndAdvancedFields() {
        val original = buildJsonObject { put("url", "https://example.com"); put("http_headers", buildJsonObject { put("X-Existing", "keep") }); put("oauth", buildJsonObject { put("client_id", "custom") }) }
        val result = OriginalMcpConfig.form(original, "https://example.com/new", "", "", "", "", "", "", "", "", true, true)
        assertEquals(original["http_headers"], result["http_headers"])
        assertEquals(original["oauth"], result["oauth"])
    }
}
