package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class OriginalAlignmentTest {
    @Test fun oauthOmissionAndExplicitEmptyScopesStayDifferent(){
        assertFalse("scopes" in McpLoginOptions().params("server",null))
        assertEquals(JsonArray(emptyList()),McpLoginOptions(scopes=emptyList(),overrideScopes=true).params("server",null)["scopes"])
    }
    @Test fun richInputsKeepImageAudioSkillAndMentionTypes(){
        val extra=Json.parseToJsonElement("""[{"type":"image","fileId":"f"},{"type":"localAudio","path":"clip.wav"},{"type":"skill","name":"技能","path":"SKILL.md"},{"type":"mention","name":"资料","path":"readme.md"}]""").jsonArray
        val result=OriginalInputContract.input("描述",extra)
        assertEquals(listOf("text","image","localAudio","skill","mention"),result.map{it.jsonObject.getValue("type").jsonPrimitive.content})
        assertFailsWith<IllegalArgumentException>{OriginalInputContract.input("",Json.parseToJsonElement("""[{"type":"future"}]""").jsonArray)}
    }
    @Test fun realtimeOptionsKeepNullPromptAndVoiceWithoutOverwritingThreadIdentity(){
        val options=OriginalRealtimeOptions.parse("""{"version":"v3","voice":"marin","prompt":null,"initialItems":[{"role":"user","text":"开始"}]}""")
        assertEquals(JsonNull,options["prompt"]);assertEquals("marin",options["voice"]?.jsonPrimitive?.content)
        assertFailsWith<IllegalArgumentException>{OriginalRealtimeOptions.parse("""{"threadId":"other"}""")}
    }
    @Test fun emptyPromptForkDoesNotInjectStdinSentinel(){
        val plan=OriginalStandaloneCommands.exec("",buildJsonObject{},"fork","session")
        assertEquals(listOf("exec","--color=never","fork","session"),plan.arguments)
    }
    @Test fun execPreservesLiteralConfigAndPromptPlusStdin(){
        val override="model_reasoning_effort="high""
        val options=buildJsonObject{put("config",JsonArray(listOf(JsonPrimitive(override))));put("stdin","context");put("color","never")}
        val plan=OriginalStandaloneCommands.exec("question",options)
        assertTrue("--config=$override" in plan.arguments)
        assertEquals(listOf("--","question"),plan.arguments.takeLast(2));assertEquals("context",plan.input)
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.exec("q",buildJsonObject{put("config",JsonArray(listOf(JsonPrimitive("cli_auth_credentials_store="file""))))})}
    }
    @Test fun turnOptionsPreserveNullAndRejectOwnedIdentity(){
        val options=OriginalTurnOptions.parse("""{"serviceTier":null,"outputSchema":{"type":"object"},"disabledPluginIds":[]}""")
        assertEquals(JsonNull,options["serviceTier"]);assertEquals(JsonArray(emptyList()),options["disabledPluginIds"])
        assertFailsWith<IllegalArgumentException>{OriginalTurnOptions.parse("""{"threadId":"other"}""")}
    }
    @Test fun launchPermissionsCannotBeMixedWithSandboxOrOverrideVaultPolicy(){
        assertEquals("profile",OriginalThreadLaunchOptions.parse("""{"permissions":"profile"}""")["permissions"]?.jsonPrimitive?.content)
        assertFailsWith<IllegalArgumentException>{OriginalThreadLaunchOptions.parse("""{"permissions":"profile","sandbox":"danger-full-access"}""")}
        assertFailsWith<IllegalArgumentException>{OriginalThreadLaunchOptions.parse("""{"config":{"cli_auth_credentials_store":"file"}}""")}
    }
    @Test fun explicitNewThreadPolicyWinsOverInheritedSelection(){
        val explicit=OriginalThreadLaunchOptions.parse("""{"permissions":"new"}""")
        assertEquals(explicit,OriginalThreadLaunchOptions.inherit(explicit,"old",null))
        assertEquals("old",OriginalThreadLaunchOptions.inherit(buildJsonObject{},"old",null)["permissions"]?.jsonPrimitive?.content)
    }
    @Test fun versionsCompareTheActualBuildTokenAndKeepUnknownExplicit(){
        assertTrue(OriginalCoreVersions.compare("codex-cli 1.2.3","codex_android/1.2.3 (Android)").contains("一致"))
        assertTrue(OriginalCoreVersions.compare("codex-cli 1.2.4","codex_android/1.2.3 (Android)").contains("不一致"))
        assertTrue(OriginalCoreVersions.compare("unknown","unknown").contains("无法识别"))
        assertFailsWith<IllegalArgumentException>{OriginalAppServerOptions.parse("""{"codeModeHost":"https://user:secret@host/"}""")}
    }
}
