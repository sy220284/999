package cn.codex.core.original

import java.io.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.Base64
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*

/** 测试源码，当前未运行；只描述客户端范围/字节/监听状态，不运行手机文件 RPC。 */
class OriginalFilesControllerTest {
    @Test fun projectPathsRejectEscapesAndKeepLexicalSymlinkMetadataNames() {
        val root = Files.createTempDirectory("original-files").toFile()
        val outside = Files.createTempDirectory("outside-files").toFile()
        try {
            File(root, "inside").writeText("x")
            Files.createSymbolicLink(File(root, "link").toPath(), File(root, "inside").toPath())
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            assertEquals(File(root, "link").absolutePath, OriginalFileData.path(root, "link"))
            assertFailsWith<IllegalArgumentException> { OriginalFileData.path(root, "../outside") }
            assertFailsWith<IllegalArgumentException> { OriginalFileData.path(root, "escape/file") }
            assertFailsWith<IllegalArgumentException> { OriginalFileData.path(root, ".", destructive = true) }
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun strictUtf8AndBinaryContentAreNeverSilentlyRewritten() {
        assertFailsWith<IllegalArgumentException> { OriginalFileData.payload(buildJsonObject {}) }
        assertFailsWith<IllegalArgumentException> { OriginalFileData.payload(buildJsonObject { put("dataBase64", 123) }) }
        val text = "中文🙂\n"
        assertEquals(text, OriginalFileData.text(Base64.getEncoder().encodeToString(text.toByteArray())))
        assertNull(OriginalFileData.text(Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))))
        assertNull(OriginalFileData.text(Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2))))
        assertFailsWith<IllegalArgumentException> { OriginalFileData.requireUnchanged("old", "new") }
    }
    @Test fun watchIdentityRejectsStaleEventsAndKeepsEarlyEvents() {
        val watching = OriginalFilesState(watchId = "new", watchPath = "/project", watchConfirmed = false)
        fun event(id: String, vararg paths: String) = buildJsonObject { put("watchId", id); put("changedPaths", buildJsonArray { paths.forEach { add(it) } }) }
        assertEquals(watching, OriginalFileData.changed(watching, event("old", "/project/old")))
        val early = OriginalFileData.changed(watching, event("new", "/project/file"))
        assertEquals(listOf("/project/file"), early.changedPaths)
        assertFalse(early.watchConfirmed)
        assertTrue(OriginalFileData.changed(early.copy(watchId = null), event("new", "/project/stale")).changedPaths == early.changedPaths)
    }
    private class Wire(scope: CoroutineScope) : Closeable {
        private val input = PipedInputStream(65536)
        private val server = PipedOutputStream(input)
        private val receiver = PipedInputStream(65536)
        val reader = receiver.bufferedReader()
        val rpc = AppServerConnection(input, PipedOutputStream(receiver), scope, { server.close() })
        suspend fun read() = withContext(Dispatchers.IO) { Json.parseToJsonElement(reader.readLine()).jsonObject }
        fun reply(request: JsonObject, result: JsonObject) { server.write((buildJsonObject { put("id", request.getValue("id")); put("result", result) }.toString() + "\n").toByteArray()); server.flush() }
        override fun close() { rpc.close(); reader.close() }
    }
    @Test fun changedFileNeverReachesTheWriteEndpointOrReportsConfirmedMutation() = runBlocking {
        val root = Files.createTempDirectory("file-conflict").toFile()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val outcomes = mutableListOf<Boolean>()
        val lifecycle = object : OriginalMutationLifecycle { override fun begin() = "ticket"; override fun finish(ticket: String, confirmed: Boolean) { outcomes += confirmed } }
        try { Wire(scope).use { wire ->
            val path = File(root, "file.txt").absolutePath
            val encode: (String) -> String = { Base64.getEncoder().encodeToString(it.toByteArray()) }
            val controller = OriginalFilesController(scope, root, { false }, lifecycle)
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.write(OriginalFileDocument(path, encode("original"), "original"), path, "replacement") }
            val request = wire.read()
            assertEquals("fs/readFile", request.getValue("method").jsonPrimitive.content)
            wire.reply(request, buildJsonObject { put("dataBase64", encode("external edit")) })
            withTimeout(3000) { controller.state.first { !it.busy && it.error != null } }
            assertEquals(listOf(false), outcomes); assertFalse(wire.reader.ready())
            assertFalse(File(path).exists())
        } } finally { scope.cancel(); dispatcher.close(); root.deleteRecursively() }
    }
    @Test fun earlyWatchNotificationSurvivesAcknowledgementButNotRebinding() = runBlocking {
        val root = Files.createTempDirectory("file-watch").toFile()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { Wire(scope).use { wire ->
            val controller = OriginalFilesController(scope, root, { false })
            withContext(dispatcher) { controller.bind(OriginalCodexClient(wire.rpc)); controller.watch() }
            val request = wire.read(); val params = request.getValue("params").jsonObject
            val id = params.getValue("watchId").jsonPrimitive.content
            val notification = buildJsonObject { put("watchId", id); put("changedPaths", buildJsonArray { add(File(root, "early").absolutePath) }) }
            withContext(dispatcher) { controller.changed(notification) }
            wire.reply(request, buildJsonObject { put("path", root.canonicalPath) })
            val active = withTimeout(3000) { controller.state.first { !it.busy && it.watchConfirmed } }
            assertEquals(1, active.changedPaths.size)
            withContext(dispatcher) { controller.bind(null); controller.changed(notification) }
            assertTrue(controller.state.value.changedPaths.isEmpty()); assertNull(controller.state.value.watchId)
        } } finally { scope.cancel(); dispatcher.close(); root.deleteRecursively() }
    }

}
