package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.io.*
import java.util.concurrent.Executors

/** 测试源码，当前未执行。 */
class OriginalSkillsTest {
    @Test fun remoteSkillUsesTheBackendIdAndRejectsALocalOnlyPlugin() {
        val plugin = OriginalPlugin("market", null, buildJsonObject { put("id", "local-id"); put("name", "display-name"); put("remotePluginId", "backend-id") })
        val params = OriginalSkillConfig.remoteParams(plugin, "skill")
        assertEquals("backend-id", params.string("remotePluginId")); assertEquals("market", params.string("remoteMarketplaceName"))
        assertFailsWith<IllegalArgumentException> { OriginalSkillConfig.remoteParams(plugin.copy(marketplacePath = "/local"), "skill") }
    }
    @Test fun extraRootsPreserveSpacesAndAllowExplicitClearing() {
        assertEquals(emptyList(), OriginalSkillConfig.roots("\n"))
        assertEquals(listOf("/skills with spaces"), OriginalSkillConfig.roots("/skills with spaces\n/skills with spaces"))
        assertFailsWith<IllegalArgumentException> { OriginalSkillConfig.roots("relative/path") }
    }
    @Test fun skillToggleReadsActualStateInsteadOfOnlyTrustingTheEchoedAcknowledgement() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val input = PipedInputStream(65536); val server = PipedOutputStream(input)
        val requests = PipedInputStream(65536); val reader = requests.bufferedReader()
        val rpc = AppServerConnection(input, PipedOutputStream(requests), scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(request: JsonObject, response: JsonObject) { server.write((buildJsonObject { put("id", request.getValue("id")); put("result", response) }.toString() + "\n").toByteArray()); server.flush() }
        val skill = buildJsonObject { put("name", "test"); put("path", "/skills/test/SKILL.md"); put("enabled", true); put("scope", "user") }
        fun skills() = buildJsonObject { put("data", buildJsonArray { add(buildJsonObject { put("cwd", "/project"); put("skills", buildJsonArray { add(skill) }); put("errors", buildJsonArray {}) }) }) }
        try {
            val controller = OriginalExtensionsController(scope, "/project", activeTask = { false })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(rpc)); controller.refreshSkills() }
            reply(read(), skills()); withTimeout(3000) { controller.state.first { !it.busy && it.skills.isNotEmpty() } }
            withContext(dispatcher) { controller.setSkillEnabled(skill, false) }
            val write = read(); assertEquals("skills/config/write", write.string("method"))
            assertEquals("/skills/test/SKILL.md", write.getValue("params").jsonObject.string("path")); assertFalse(write.getValue("params").jsonObject.containsKey("name"))
            reply(write, buildJsonObject { put("effectiveEnabled", false) })
            val refresh = read(); assertEquals("skills/list", refresh.string("method")); assertEquals(JsonPrimitive(true), refresh.getValue("params").jsonObject["forceReload"])
            reply(refresh, skills())
            val after = withTimeout(3000) { controller.state.first { !it.busy && it.skillNotice.contains("仍") } }
            assertTrue(after.skills.single().getValue("enabled").jsonPrimitive.boolean)
            assertFalse(reader.ready())
        } finally { rpc.close(); scope.cancel(); reader.close(); dispatcher.close() }
    }
}
