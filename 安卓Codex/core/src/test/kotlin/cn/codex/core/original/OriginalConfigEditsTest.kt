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
    @Test fun lastUserLayerMatchesUpstreamProfileSelection() {
        val snapshot = OriginalConfigEdits.snapshot(buildJsonObject { put("layers", buildJsonArray {
            add(layer("user", "base")); add(layer("project", "project")); add(layer("user", "profile"))
        }) })!!
        assertEquals("profile", snapshot.version)
        assertEquals("/config/profile.toml", snapshot.file)
    }
    @Test fun missingDisabledAndUnversionedUserLayersAreReadOnly() {
        assertNull(OriginalConfigEdits.snapshot(buildJsonObject {}))
        for (entry in listOf(layer("user", "", false), layer("user", "disabled", true))) {
            assertNull(OriginalConfigEdits.snapshot(buildJsonObject { put("layers", buildJsonArray { add(entry) }) }))
        }
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
}
