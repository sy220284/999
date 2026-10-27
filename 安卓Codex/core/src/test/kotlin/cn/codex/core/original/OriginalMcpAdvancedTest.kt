package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 静态测试源码；当前未运行 JVM/Gradle。 */
class OriginalMcpAdvancedTest {
    private fun http() = buildJsonObject {
        put("url", "https://mcp.example/service")
        put("oauth", buildJsonObject { put("client_id", "already-set") })
        put("custom", "preserve-existing-value")
    }
    @Test fun independentToolPolicyKeepsUnknownFieldsAndOtherTools() {
        val base = JsonObject(http() + ("tools" to buildJsonObject {
            put("read", buildJsonObject { put("approval_mode", "prompt") })
        }))
        val edited = OriginalMcpAdvanced.tool(base, "write", "writes", "250")
        assertEquals("prompt", edited["tools"]!!.jsonObject["read"]!!.jsonObject["approval_mode"]!!.jsonPrimitive.content)
        val newPolicy = edited["tools"]!!.jsonObject["write"]!!.jsonObject
        assertEquals("writes", newPolicy["approval_mode"]!!.jsonPrimitive.content)
        assertEquals(250L, newPolicy["output_token_limit"]!!.jsonPrimitive.long)
        assertEquals(base["oauth"], edited["oauth"])
        assertEquals(base["custom"], edited["custom"])
        val removed = OriginalMcpAdvanced.tool(edited, "write", "", "")
        assertNull(removed["tools"]!!.jsonObject["write"])
    }
    @Test fun emptyAllowListRemovesRestrictionAndDoesNotTouchDenyList() {
        val original = JsonObject(http() + ("enabled_tools" to buildJsonArray { add("search") }))
        val result = OriginalMcpAdvanced.lists(original, "", "delete\nupdate", "prompt")
        assertNull(result["enabled_tools"])
        assertEquals(listOf("delete", "update"), result["disabled_tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("prompt", result["default_tools_approval_mode"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.lists(http(), "search\nsearch", "", "") }
    }
    @Test fun environmentReferencesKeepSourcesAndDoNotStoreSecretValues() {
        val base = buildJsonObject {
            put("command", "/system/bin/sh")
            put("env_vars", buildJsonArray { add("PATH"); add(buildJsonObject { put("name", "TOKEN"); put("source", "remote") }) })
        }
        val added = OriginalMcpAdvanced.reference(base, "HOME", "local")
        assertEquals(3, added["env_vars"]!!.jsonArray.size)
        val replaced = OriginalMcpAdvanced.reference(added, "TOKEN", "local")
        assertEquals(3, replaced["env_vars"]!!.jsonArray.size)
        val token = replaced["env_vars"]!!.jsonArray.mapNotNull { it as? JsonObject }
            .single { it["name"]?.jsonPrimitive?.content == "TOKEN" }
        assertEquals("local", token["source"]!!.jsonPrimitive.content)
        val removed = OriginalMcpAdvanced.reference(replaced, "PATH", "remove")
        assertEquals(2, removed["env_vars"]!!.jsonArray.size)
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.reference(base, "KEY", "other") }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.reference(http(), "KEY", "local") }
    }
    @Test fun rejectsBadNumbersPolicySourceAndMixedTransports() {
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.tool(http(), "write", "approve", "0") }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.tool(http(), "write", "unsafe", "3") }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(JsonObject(http() + ("tools" to buildJsonObject {
            put("write", buildJsonObject { put("output_token_limit", -1) })
        }))) }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject { put("command", "echo"); put("url", "https://mcp.example") }) }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject {
            put("command", "echo"); put("env_vars", buildJsonArray { add(buildJsonObject { put("name", "KEY"); put("source", "unknown") }) })
        }) }
    }
}
