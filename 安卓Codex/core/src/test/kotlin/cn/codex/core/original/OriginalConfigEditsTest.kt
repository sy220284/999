package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 回归测试源码；按用户要求暂不执行编译型测试。 */
class OriginalConfigEditsTest {
    private fun layer(type: String, version: String, disabled: Boolean = false) = buildJsonObject {
        put("name", buildJsonObject { put("type", type); put("file", "/config/$version.toml") })
        put("version", version); put("disabledReason", if (disabled) JsonPrimitive("policy") else JsonNull)
    }
    @Test fun firstWireUserLayerMatchesUpstreamActiveProfileSelection() {
        val snapshot = OriginalConfigEdits.snapshot(buildJsonObject { put("layers", buildJsonArray {
            add(layer("project", "project")); add(layer("user", "profile")); add(layer("user", "base"))
        }) })!!
        assertEquals("profile", snapshot.version)
        assertEquals("/config/profile.toml", snapshot.file)
    }
    @Test fun missingDisabledAndUnversionedUserLayersAreReadOnly() {
        assertNull(OriginalConfigEdits.snapshot(buildJsonObject {}))
        for (entry in listOf(layer("user", "", false), layer("user", "disabled", true))) {
            assertNull(OriginalConfigEdits.snapshot(buildJsonObject { put("layers", buildJsonArray { add(entry) }) }))
        }
        // 不能跳过活动但禁用的 profile，误写到较低优先级的 base。
        assertNull(OriginalConfigEdits.snapshot(buildJsonObject { put("layers", buildJsonArray {
            add(layer("user", "profile", true)); add(layer("user", "base"))
        }) }))
    }
    @Test fun pluginKeysAreNestedDataAndWritesCarryExactVersion() {
        val snapshot = OriginalConfigSnapshot("/user/config.toml", "revision-3")
        val request = OriginalConfigEdits.request(snapshot, OriginalConfigEdits.plugin("name.with.dot@market", false))
        assertEquals("revision-3", request.getValue("expectedVersion").jsonPrimitive.content)
        assertTrue(request.getValue("reloadUserConfig").jsonPrimitive.boolean)
        val edit = request.getValue("edits").jsonArray.single().jsonObject
        assertEquals("plugins", edit.getValue("keyPath").jsonPrimitive.content)
        assertEquals("upsert", edit.getValue("mergeStrategy").jsonPrimitive.content)
        assertFalse(edit.getValue("value").jsonObject.getValue("name.with.dot@market").jsonObject.getValue("enabled").jsonPrimitive.boolean)
    }
    @Test fun hookTrustRequiresSameSourceHashAndNeverImplicitlyEnables() {
        fun hook(hash: String = "sha256:abc", managed: Boolean = false) = buildJsonObject {
            put("key", "file.with.dots/key"); put("currentHash", hash); put("isManaged", managed); put("trustStatus", "untrusted")
        }
        val edit = OriginalConfigEdits.hook(hook(), hook(), trust = true)
        val state = edit.getValue("value").jsonObject.getValue("file.with.dots/key").jsonObject
        assertEquals("sha256:abc", state.getValue("trusted_hash").jsonPrimitive.content)
        assertFalse(state.containsKey("enabled"))
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hook(hook(), hook("changed"), true) }
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hook(hook(managed = true), hook(managed = true), true) }
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hook(hook(""), hook(""), true) }
        val unknown = JsonObject(hook() + ("trustStatus" to JsonPrimitive("future")))
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hook(unknown, unknown, false) }
    }
    @Test fun revokingUserTrustAtomicallyRemovesBothHashesAndDisablesWithoutErasingOtherFields() {
        val hook = buildJsonObject { put("key", "file.with.dots/key"); put("currentHash", "abc"); put("isManaged", false); put("trustStatus", "trusted") }
        val edits = OriginalConfigEdits.revokeHook(hook, hook)
        val request = OriginalConfigEdits.request(OriginalConfigSnapshot("/config.toml", "v1"), edits)
        assertEquals(3, request.getValue("edits").jsonArray.size)
        assertEquals("hooks.state.\"file.with.dots/key\".trusted_hash", edits[0].getValue("keyPath").jsonPrimitive.content)
        assertEquals(JsonNull, edits[0]["value"]); assertEquals(JsonNull, edits[1]["value"])
        assertFalse(edits[2].getValue("value").jsonObject.getValue("file.with.dots/key").jsonObject.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun ruleEditingDoesNotReplaceTrustStateAndCanDeleteOneEvent() {
        val old = buildJsonObject { put("state", buildJsonObject { put("key", buildJsonObject { put("trusted_hash", "preserve") }) }); put("PreToolUse", buildJsonArray {}); put("Stop", buildJsonArray {}) }
        val next = buildJsonObject { put("Stop", buildJsonArray {}) }
        val edits = OriginalConfigEdits.hookRules(old, next)
        assertEquals(2, edits.size)
        assertTrue(edits.none { it.getValue("keyPath").jsonPrimitive.content.contains("state") })
        assertEquals(JsonNull, edits.first { it.getValue("keyPath").jsonPrimitive.content.contains("PreToolUse") }["value"])
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hookRules(old, buildJsonObject { put("state", buildJsonObject {}) }) }
    }

    @Test fun rulesRejectMalformedMatcherGroupsAndMissingHandlers() {
        val empty = buildJsonObject {}
        val missing = buildJsonObject { put("PreToolUse", buildJsonArray { add(buildJsonObject { put("matcher", "git") }) }) }
        val invalid = buildJsonObject { put("PreToolUse", buildJsonArray { add(buildJsonObject {
            put("matcher", 7); put("hooks", buildJsonArray { add(buildJsonObject { put("type", "command"); put("command", "echo ok") }) })
        }) }) }
        val blank = buildJsonObject { put("PreToolUse", buildJsonArray { add(buildJsonObject {
            put("hooks", buildJsonArray { add(buildJsonObject { put("type", "command"); put("command", "") }) })
        }) }) }
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hookRules(empty, missing) }
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hookRules(empty, invalid) }
        assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hookRules(empty, blank) }
    }

    @Test fun rulesKeepAdvancedHandlersAndDoNotChangeTrustState() {
        val previous = buildJsonObject { put("state", buildJsonObject { put("id", buildJsonObject { put("trusted_hash", "abc") }) }) }
        val rules = buildJsonObject { put("PreToolUse", buildJsonArray { add(buildJsonObject {
            put("matcher", "git"); put("hooks", buildJsonArray { add(buildJsonObject {
                put("type", "prompt")
            }) })
        }) }) }
        val edits = OriginalConfigEdits.hookRules(previous, rules)
        assertEquals(1, edits.size)
        assertEquals("hooks.\"PreToolUse\"", edits.single().getValue("keyPath").jsonPrimitive.content)
        assertEquals(rules["PreToolUse"], edits.single()["value"])
    }

    @Test fun supportedHandlersMatchOriginalFourVariantsAndPreserveWindowsOptions() {
        val valid = buildJsonObject {
            put("PreToolUse", buildJsonArray {
                add(buildJsonObject { put("hooks", buildJsonArray {
                    add(buildJsonObject { put("type", "command"); put("command", "echo hi"); put("commandWindows", "cmd /c echo hi")
                        put("async", true); put("timeout", 10); put("additionalContextLimit", 0) })
                    add(buildJsonObject { put("type", "mcp_tool"); put("server", "secure"); put("tool", "scan")
                        put("input", buildJsonObject { put("path", "${tool_input.path}"); put("strict", true) }) })
                    add(buildJsonObject { put("type", "prompt") })
                    add(buildJsonObject { put("type", "agent") })
                } ) })
            })
        }
        assertEquals(valid["PreToolUse"], OriginalConfigEdits.hookRules(buildJsonObject {}, valid).single()["value"])
    }
    @Test fun unknownHandlerAndMcpNullInputAreRejectedBeforeWrite() {
        fun rule(handler: JsonObject) = buildJsonObject { put("PreToolUse", buildJsonArray {
            add(buildJsonObject { put("hooks", buildJsonArray { add(handler) }) })
        }) }
        listOf(
            buildJsonObject { put("type", "future_handler"); put("command", "echo hi") },
            buildJsonObject { put("type", "mcp_tool"); put("server", "scanner"); put("tool", "scan"); put("input", buildJsonObject { put("value", JsonNull) }) },
            buildJsonObject { put("type", "command"); put("command", "echo ok"); put("timeout", -1) },
            buildJsonObject { put("type", "agent"); put("unknown", "bad") },
            buildJsonObject { put("type", "prompt"); put("prompt", "unrecognized") }
        ).forEach { handler ->
            assertFailsWith<IllegalArgumentException> { OriginalConfigEdits.hookRules(buildJsonObject {}, rule(handler)) }
        }
    }

}
