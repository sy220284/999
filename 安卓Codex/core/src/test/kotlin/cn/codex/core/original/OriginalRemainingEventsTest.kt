package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files
import java.io.File

/** 行为测试源码；本阶段不执行编译型测试。 */
class OriginalRemainingEventsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun sparseQuotaUpdatesPreserveNullableMetadataAndMergeWindows() {
        val old = obj("""{"planType":"pro","primary":{"usedPercent":1,"windowDurationMins":60}}""")
        val value = OriginalRemainingEvents.mergeSparse(old, obj("""{"planType":null,"primary":{"usedPercent":2}}"""))
        assertEquals("pro", value.getValue("planType").jsonPrimitive.content)
        assertEquals(60, value.getValue("primary").jsonObject.getValue("windowDurationMins").jsonPrimitive.int)
        assertEquals(2, value.getValue("primary").jsonObject.getValue("usedPercent").jsonPrimitive.int)
    }
    @Test fun processStreamsPreserveSplitUtf8AndExitDoesNotDuplicateStreamedText() {
        val events = OriginalRemainingEvents(); val bytes = "中".toByteArray()
        fun chunk(data: ByteArray) = buildJsonObject { put("processHandle", "p");put("stream", "stdout");put("deltaBase64", java.util.Base64.getEncoder().encodeToString(data));put("capReached", false) }
        events.accept("process/outputDelta", chunk(bytes.copyOfRange(0,1)))
        events.accept("process/outputDelta", chunk(bytes.copyOfRange(1,3)))
        events.accept("process/exited", obj("""{"processHandle":"p","exitCode":0,"stdout":"","stderr":"","stdoutCapReached":false,"stderrCapReached":false}"""))
        assertEquals("中", events.state.processes.getValue("p").stdout)
        assertEquals(0, events.state.processes.getValue("p").exitCode)
    }
    @Test fun realtimeDoneReplacesPendingDeltaAndClosingStopsLatePartialText() {
        val events = OriginalRemainingEvents()
        events.accept("thread/realtime/started", obj("""{"threadId":"t","realtimeSessionId":"s","version":"v2"}"""))
        events.accept("thread/realtime/transcript/delta", obj("""{"threadId":"t","role":"assistant","delta":"前半"}"""))
        events.accept("thread/realtime/transcript/done", obj("""{"threadId":"t","role":"assistant","text":"完整正文"}"""))
        events.accept("thread/realtime/closed", obj("""{"threadId":"t","reason":"done"}"""))
        events.accept("thread/realtime/transcript/delta", obj("""{"threadId":"t","role":"assistant","delta":"迟到"}"""))
        val rt = events.state.realtime.getValue("t")
        assertEquals("完整正文", rt.transcripts.single().second); assertTrue(rt.partials.isEmpty()); assertFalse(rt.active)
    }
    @Test fun dynamicToolsReadRealProjectDataAndRejectUnknownNamesOrEscapes() {
        val root = Files.createTempDirectory("dynamic-tools").toFile()
        try {
            File(root,"a.txt").writeText("真实正文")
            val tools = OriginalAndroidTools(root)
            val call = obj("""{"namespace":null,"tool":"android_project_read","arguments":{"path":"a.txt"}}""")
            assertTrue(tools.execute(call).getValue("success").jsonPrimitive.boolean)
            assertTrue(tools.execute(call).toString().contains("真实正文"))
            assertFalse(tools.execute(obj("""{"tool":"android_project_read","arguments":{"path":"../outside"}}""")).getValue("success").jsonPrimitive.boolean)
            assertFalse(tools.execute(obj("""{"tool":"shell","arguments":{}}""")).getValue("success").jsonPrimitive.boolean)
        } finally { root.deleteRecursively() }
    }
    @Test fun completedSearchRetainsItsOwnResultsAndNewSessionIsSeparate() {
        val events = OriginalRemainingEvents()
        events.accept("fuzzyFileSearch/sessionUpdated", obj("""{"sessionId":"a","query":"one","files":[{"path":"a"}]}"""))
        events.accept("fuzzyFileSearch/sessionCompleted", obj("""{"sessionId":"a"}"""))
        events.accept("fuzzyFileSearch/sessionUpdated", obj("""{"sessionId":"b","query":"two","files":[]}"""))
        assertTrue(events.state.searches.getValue("a").completed)
        assertEquals(1,events.state.searches.getValue("a").data.getValue("files").jsonArray.size)
    }
    @Test fun resetClearsAllConnectionScopedState() {
        val events = OriginalRemainingEvents()
        events.accept("configWarning", obj("""{"summary":"配置冲突","details":"检查覆盖层"}"""))
        assertTrue(events.state.notices.isNotEmpty());events.reset();assertEquals(OriginalEventState(),events.state)
    }
    @Test fun registeredToolSchemaRequiresReadPathAndRejectsNonStringPaths() {
        val definitions = OriginalAndroidTools.definitions()
        val read = definitions.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "android_project_read" }
        assertEquals(listOf("path"),read.getValue("inputSchema").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val root = Files.createTempDirectory("dynamic-tool-path").toFile()
        try { assertFalse(OriginalAndroidTools(root).execute(obj("""{"tool":"android_project_list","arguments":{"path":3}}""")).getValue("success").jsonPrimitive.boolean) }
        finally { root.deleteRecursively() }
    }
    @Test fun canonicalRealtimeTranscriptAvoidsLegacyFallbackAndTracksAudioBytes() {
        val events = OriginalRemainingEvents()
        events.accept("thread/realtime/started", obj("""{"threadId":"t","realtimeSessionId":"s"}"""))
        events.accept("thread/realtime/item/completed",obj("""{"threadId":"t","item":{"id":"i","realtimeSessionId":"s","type":"transcriptSegment","role":"assistant","text":"全文"}}"""))
        events.accept("thread/realtime/outputAudio/delta",obj("""{"threadId":"t","audio":{"data":"AAA=","sampleRate":24000,"numChannels":1}}"""))
        assertTrue(events.state.realtime.getValue("t").canonicalText)
        assertEquals(2L,events.state.realtime.getValue("t").audioBytes)
        events.accept("thread/realtime/item/completed",obj("""{"threadId":"t","item":{"id":"old","realtimeSessionId":"older","type":"transcriptSegment","text":"旧会话"}}"""))
        assertFalse("old" in events.state.realtime.getValue("t").items)
    }

}
