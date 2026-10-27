package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 静态测试源码；当前未运行 JVM/Gradle。 */
class OriginalMcpAdvancedTest {
    private fun http() = buildJsonObject {
        put("url", "https://mcp.example/service")
        put("oauth", buildJsonObject { put("client_id", "already-set") })
        put("startup_readiness", "catalog")
    }
    @Test fun independentToolPolicyKeepsValidAdvancedFieldsAndOtherTools() {
        val base = JsonObject(http() + ("tools" to buildJsonObject {
            put("read", buildJsonObject { put("approval_mode", "prompt") })
        }))
        val edited = OriginalMcpAdvanced.tool(base, "write", "writes", "250")
        assertEquals("prompt", edited["tools"]!!.jsonObject["read"]!!.jsonObject["approval_mode"]!!.jsonPrimitive.content)
        val newPolicy = edited["tools"]!!.jsonObject["write"]!!.jsonObject
        assertEquals("writes", newPolicy["approval_mode"]!!.jsonPrimitive.content)
        assertEquals(250L, newPolicy["output_token_limit"]!!.jsonPrimitive.long)
        assertEquals(base["oauth"], edited["oauth"])
        assertEquals(base["startup_readiness"], edited["startup_readiness"])
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
    @Test fun strictUpstreamFieldsRejectUnsupportedAndWrongTypedValues() {
        val examples = listOf(
            buildJsonObject { put("url", "https://example.com"); put("unknown_new_key", true) },
            buildJsonObject { put("url", "https://example.com"); put("bearer_token", "secret") },
            buildJsonObject { put("url", "https://example.com"); put("tool_input_schema_max_bytes", 0) },
            buildJsonObject { put("url", "https://example.com"); put("startup_timeout_sec", "2") },
            buildJsonObject { put("url", "https://example.com"); put("required", "false") },
            buildJsonObject { put("url", "https://example.com"); put("startup_readiness", "unknown") },
            buildJsonObject { put("url", "https://example.com"); put("omit_tools_from", buildJsonArray { add("not_a_surface") }) },
            buildJsonObject { put("url", "https://example.com"); put("env_vars", buildJsonArray { add("TOKEN") }) },
            buildJsonObject { put("command", "/system/bin/sh"); put("http_headers", buildJsonObject {}) },
            buildJsonObject { put("command", "/system/bin/sh"); put("oauth", buildJsonObject {}) }
        )
        examples.forEach { assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(it) } }
    }
    @Test fun oauthEnterprisePolicyAndHeadersMatchOriginalSource() {
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject {
            put("url", "https://example.com"); put("oauth", buildJsonObject { put("client_secret", "hidden") })
        }) }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject {
            put("url", "https://example.com"); put("oauth", buildJsonObject { put("authorization_server_issuer", "https://auth.example") })
        }) }
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject {
            put("url", "https://example.com"); put("environment_id", "remote")
            put("http_headers_helper", "request-headers")
        }) }
        val valid = buildJsonObject {
            put("url", "https://example.com"); put("auth", "ema_auth")
            put("oauth", buildJsonObject {
                put("client_id", "approved"); put("client_secret", "existing-secret-reference")
                put("authorization_server_issuer", "https://auth.example")
            })
            put("http_headers", buildJsonObject { put("Accept", "application/json") })
            put("env_http_headers", buildJsonObject { put("Authorization", "MCP_AUTH_VAR") })
            put("tool_input_schema_max_bytes", 4096)
        }
        assertEquals(valid, OriginalMcpAdvanced.validate(valid))
    }
    @Test fun literalEmptyStdioArgumentsRemainValidAndToolsUsePositiveBudget() {
        val value = buildJsonObject {
            put("command", "/system/bin/sh")
            put("args", buildJsonArray { add(""); add(" -c "); add("") })
            put("tools", buildJsonObject { put("scan", buildJsonObject { put("output_token_limit", 1) }) })
        }
        assertEquals(value, OriginalMcpAdvanced.validate(value))
        assertFailsWith<IllegalArgumentException> { OriginalMcpAdvanced.validate(buildJsonObject {
            put("command", "/system/bin/sh"); put("tools", buildJsonObject { put("scan", buildJsonObject { put("output_token_limit", "8") }) })
        }) }
    }

}
