package cn.codex.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class RuntimeTest {
    private fun workspace() = Workspace(Files.createTempDirectory("codex-test").toFile())

    @Test fun `file writes stay inside workspace including symbolic links`() {
        val fs = workspace()
        fs.write("中文/说明.md", "你好")
        assertEquals("你好", fs.read("中文/说明.md"))
        assertFails { fs.write("../逃逸.txt", "bad") }
        val outside = Files.createTempDirectory("outside")
        Files.createSymbolicLink(fs.root.toPath().resolve("链接"), outside)
        assertFails { fs.write("链接/逃逸.txt", "bad") }
        assertFalse(Files.exists(outside.resolve("逃逸.txt")))
    }

    @Test fun `patch checks every change before mutating any file`() {
        val fs = workspace()
        fs.write("a.txt", "one\ntwo\n")
        val patch = "*** Begin Patch\n*** Add File: b.txt\n+created\n*** Update File: a.txt\n@@\n-missing\n+new\n*** End Patch"
        assertFails { fs.applyPatch(patch) }
        assertFalse(fs.resolve("b.txt").exists())
        assertEquals("one\ntwo\n", fs.read("a.txt"))
        fs.applyPatch("*** Begin Patch\n*** Update File: a.txt\n@@\n one\n-two\n+三\n*** End Patch")
        assertEquals("one\n三\n", fs.read("a.txt"))
    }

    @Test fun `stale change cannot overwrite a human edit`() {
        val fs = workspace()
        fs.write("a", "old")
        val change = fs.previewWrite("a", "new")
        fs.write("a", "human")
        assertFails { fs.commit(change) }
        assertEquals("human", fs.read("a"))
    }

    @Test fun `shell cancellation terminates the running process`() = runBlocking<Unit> {
        val shell = ShellRunner("/bin/sh", workspace().root)
        val job = launch(Dispatchers.Default) { shell.execute("sleep 30", 60) {} }
        delay(250)
        withTimeout(2500) { job.cancelAndJoin() }
        assertFalse(shell.isRunning)
    }

    @Test fun `shell returns exit code and limits retained output`() = runBlocking<Unit> {
        val shell = ShellRunner("/bin/sh", workspace().root, outputLimit = 1024)
        val r = shell.execute("printf '你好'; exit 7", 10) {}
        assertEquals(7, r.exitCode)
        assertEquals("你好", r.output)
        val big = shell.execute("yes x | head -c 8000", 10) {}
        assertTrue(big.truncated)
        assertTrue(big.output.length <= 1024)
    }

    @Test fun `deleting a session updates the index and does not touch other sessions`() {
        val store = SessionStore(Files.createTempDirectory("delete-sessions").toFile())
        val original = Session(project = "项目", title = "保留")
        val doomed = Session(project = "项目", title = "删除")
        store.save(original)
        store.save(doomed)
        store.delete(doomed.id)
        assertEquals(listOf(original.id), store.list(includeArchived = true).map { it.id })
        assertEquals("保留", store.load(original.id).title)
        assertFails { store.load(doomed.id) }
    }

    @Test fun `session fork and archive persist without changing original`() {
        val store = SessionStore(Files.createTempDirectory("sessions").toFile())
        val s = Session(project = "默认项目", title = "测试", messages = listOf(Message(role = "user", text = "你好")))
        store.save(s)
        val fork = store.fork(s.id)
        store.save(fork.copy(archived = true))
        assertEquals(1, store.list().size)
        assertEquals(s.messages, store.load(fork.id).messages)
        assertFalse(store.load(s.id).archived)
        assertEquals(2, store.list(includeArchived = true).size)
    }

    private fun stream(output: JsonArray): MockResponse {
        val done = buildJsonObject { put("type", "response.completed"); put("response", buildJsonObject { put("id", "resp_test"); put("output", output) }) }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: response.completed\ndata: $done\n\n")
    }

    @Test fun `real HTTP tool loop returns denial to model without writing file`() = runBlocking<Unit> {
        val fs = workspace()
        MockWebServer().use { server ->
            server.enqueue(stream(buildJsonArray { add(buildJsonObject { put("type", "function_call"); put("name", "write_file"); put("call_id", "call_1"); put("arguments", "{\"path\":\"danger\",\"content\":\"x\"}") }) }))
            server.enqueue(stream(Json.parseToJsonElement("""[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"已取消修改"}]}]""").jsonArray))
            val client = ResponsesClient(ApiConfig(endpoint = server.url("/v1").toString(), apiKey = "test", model = "test-model", allowLoopbackHttp = true))
            val tools = LocalTools(fs, ShellRunner("/bin/sh", fs.root))
            var asked = false
            val agent = AgentRuntime(client, tools, approve = { asked = true; false })
            val result = agent.run(Session(project = "p"), "修改文件") {}
            assertTrue(asked)
            assertFalse(fs.resolve("danger").exists())
            assertEquals("已取消修改", result.messages.last().text)
            server.takeRequest()
            val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(second["input"].toString().contains("用户拒绝"))
            assertTrue(second["input"].toString().contains("function_call_output"))
        }
    }

    @Test fun `unfinished SSE is a failure not a successful empty reply`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"半句\"}\n\n"))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "test", "m", allowLoopbackHttp = true))
            assertFailsWith<IncompleteResponse> { client.respond(JsonArray(emptyList()), emptyList()) {} }
        }
    }

    @Test fun `cancel interrupts a blocked network stream`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "test", "m", allowLoopbackHttp = true))
            val job = launch(Dispatchers.Default) { client.respond(JsonArray(emptyList()), emptyList()) {} }
            delay(250)
            withTimeout(2000) { job.cancelAndJoin() }
        }
    }

    @Test fun `multiple SSE data lines are assembled as one event`() {
        val frames = mutableListOf<String>()
        val parser = SseParser { frames += it }
        listOf(": heartbeat", "data: {\"a\":", "data: 1}", "").forEach(parser::line)
        assertEquals(listOf("{\"a\":\n1}"), frames)
    }

    @Test fun `alias paths cannot modify the same file twice in one batch`() {
        val fs = workspace()
        fs.write("a.txt", "原内容")
        val first = FileChange("a.txt", "原内容", "修改一")
        val second = FileChange("./a.txt", "原内容", "修改二")
        assertFails { fs.commitAll(listOf(first, second)) }
        assertEquals("原内容", fs.read("a.txt"))
    }

    @Test fun `SSE dispatches final frame without a trailing blank line`() {
        val frames = mutableListOf<String>()
        val parser = SseParser { frames += it }
        parser.line("data: hello")
        parser.finish()
        parser.finish()
        assertEquals(listOf("hello"), frames)
    }

    @Test fun `stream accepts final completed event without a trailing blank line`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val expected = Json.parseToJsonElement("""[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"回复完成"}]}]""").jsonArray
            val event = buildJsonObject {
                put("type", "response.completed")
                put("response", buildJsonObject { put("status", "completed"); put("output", expected) })
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n"))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "test", "m", allowLoopbackHttp = true))
            assertEquals(expected, client.respond(JsonArray(emptyList()), emptyList()) {})
        }
    }

    @Test fun `model refusal remains visible in the conversation`() = runBlocking<Unit> {
        val refusal = Json.parseToJsonElement("""[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"无法完成该请求"}]}]""").jsonArray
        MockWebServer().use { server ->
            server.enqueue(stream(refusal))
            val fs = workspace()
            val agent = AgentRuntime(ResponsesClient(ApiConfig(server.url("/").toString(), "test", "m", allowLoopbackHttp = true)), LocalTools(fs, ShellRunner("/bin/sh", fs.root)), { false })
            assertEquals("无法完成该请求", agent.run(Session(project = "测试"), "任务") {}.messages.last().text)
        }
    }

    @Test fun `loopback endpoint without key does not add empty authorization header`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(stream(JsonArray(emptyList())))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "", "local", allowLoopbackHttp = true))
            client.respond(JsonArray(emptyList()), emptyList()) {}
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }


    @Test fun `context compaction keeps newest four user turns and original UI history`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val reply = Json.parseToJsonElement("""[{"type":"message","content":[{"type":"output_text","text":"已完成第一轮文件审查，剩余任务是执行验证"}]}]""").jsonArray
            server.enqueue(stream(reply))
            val turns = (1..7).flatMap { n ->
                listOf(obj("role" to "user", "content" to "问题$n"), obj("role" to "assistant", "content" to "回答$n"))
            }
            val original = Session(project = "工程", wire = JsonArray(turns), messages = listOf(Message(role = "user", text = "完整记录")))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "test", "test", allowLoopbackHttp = true))
            val result = ContextCompactor(client).compact(original)
            assertEquals(original.messages, result.messages)
            assertEquals(9, result.wire.size)
            assertTrue(result.wire[0].toString().contains("剩余任务是执行验证"))
            assertTrue(result.wire[1].toString().contains("问题4"))
            assertEquals(turns.last(), result.wire.last())
            assertEquals(14, original.wire.size)
        }
    }

    @Test fun `compaction failure does not mutate the session`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(stream(JsonArray(emptyList())))
            val original = Session(project = "工程", wire = JsonArray((1..6).map { obj("role" to "user", "content" to "提问$it") }))
            val client = ResponsesClient(ApiConfig(server.url("/").toString(), "test", "test", allowLoopbackHttp = true))
            assertFailsWith<IllegalStateException> { ContextCompactor(client).compact(original) }
            assertEquals(6, original.wire.size)
        }
    }


    @Test fun `steering inbox survives restart and only acknowledges consumed IDs`() {
        val dir = Files.createTempDirectory("steering").toFile()
        val inbox = SteeringInbox(dir)
        val first = inbox.add("session-1", "继续读取文件")
        val second = inbox.add("session-1", "然后检查结果")
        assertEquals(listOf(first, second), SteeringInbox(dir).pending("session-1", emptySet()))
        assertEquals(listOf(second), SteeringInbox(dir).pending("session-1", setOf(first.id)))
        inbox.acknowledge("session-1", setOf(first.id))
        assertEquals(listOf(second), SteeringInbox(dir).pending("session-1", emptySet()))
        inbox.clear("session-1")
        assertTrue(inbox.pending("session-1", emptySet()).isEmpty())
    }

    @Test fun `queued steering runs after model completion and persists before acknowledgement`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val firstReply = Json.parseToJsonElement("""[{"type":"message","content":[{"type":"output_text","text":"第一轮已完成"}]}]""").jsonArray
            val secondReply = Json.parseToJsonElement("""[{"type":"message","content":[{"type":"output_text","text":"追加检查已完成"}]}]""").jsonArray
            server.enqueue(stream(firstReply))
            server.enqueue(stream(secondReply))
            val inbox = SteeringInbox(Files.createTempDirectory("steering-run").toFile())
            val start = Session(project = "项目")
            val queued = inbox.add(start.id, "继续检查新增文件")
            val fs = workspace()
            val runtime = AgentRuntime(
                ResponsesClient(ApiConfig(server.url("/").toString(), "test", "test", allowLoopbackHttp = true)),
                LocalTools(fs, ShellRunner("/bin/sh", fs.root)), { false }, inbox
            )
            var saved: Session? = null
            val final = runtime.run(start, "先列出文件") { event ->
                if (event is AgentEvent.Updated) saved = event.session
            }
            assertEquals(2, final.messages.count { it.role == "assistant" })
            assertTrue(final.messages.any { it.text == "继续检查新增文件" })
            assertTrue(queued.id in final.consumedSteeringIds)
            assertEquals(final.wire, saved?.wire)
            assertTrue(inbox.pending(start.id, emptySet()).isEmpty())
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("继续检查新增文件"))
        }
    }


    @Test fun `binary copy and recursive directory copy stay inside the workspace`() {
        val fs = workspace()
        fs.resolve("原始").mkdirs()
        val bytes = byteArrayOf(0, 1, 2, -1, 127)
        fs.resolve("原始/资源.bin").writeBytes(bytes)
        fs.copyPath("原始", "副本")
        assertContentEquals(bytes, fs.resolve("副本/资源.bin").readBytes())
        assertFails { fs.copyPath("原始", "原始/子目录") }
        assertFails { fs.copyPath("../", "错误") }
        assertFails { fs.copyPath("原始", "副本") }
    }

    @Test fun `copy rejects symbolic links and leaves no partially copied directory`() {
        val fs = workspace()
        fs.resolve("源").mkdirs()
        fs.resolve("源/普通.txt").writeText("文本")
        val outside = Files.createTempFile("outside", ".txt")
        Files.createSymbolicLink(fs.resolve("源/链接").toPath(), outside)
        assertFails { fs.copyPath("源", "目标") }
        assertFalse(fs.resolve("目标").exists())
    }

    @Test fun `recursive removal requires explicit flag and cannot remove project root`() {
        val fs = workspace()
        fs.resolve("待删除").mkdirs()
        fs.write("待删除/文件", "内容")
        assertFails { fs.removePath("待删除") }
        assertFails { fs.removePath(". ", recursive = true) }
        assertFails { fs.removePath(".", recursive = true) }
        assertEquals("内容", fs.read("待删除/文件"))
        fs.removePath("待删除", recursive = true)
        assertFalse(fs.resolve("待删除").exists())
    }

    @Test fun `tool denied copy and delete have no side effects`() = runBlocking<Unit> {
        val fs = workspace()
        fs.write("a", "安全")
        val tools = LocalTools(fs, ShellRunner("/bin/sh", fs.root))
        val copy = ToolCall("copy-1", "copy_path", obj("source" to "a", "destination" to "b"))
        val del = ToolCall("delete-1", "remove_path", obj("path" to "a"))
        assertTrue(tools.execute(copy, { false }, {}).contains("拒绝"))
        assertTrue(tools.execute(del, { false }, {}).contains("拒绝"))
        assertEquals("安全", fs.read("a"))
        assertFalse(fs.resolve("b").exists())
    }


    @Test fun `tool attempt journal persists state transitions and removes only acknowledged calls`() {
        val dir = Files.createTempDirectory("attempts").toFile()
        val journal = ToolAttemptJournal(dir)
        val call = ToolCall("call-1", "write_file", obj("path" to "test", "content" to "hello"))
        journal.begin("session-1", listOf(call))
        assertEquals("pending", ToolAttemptJournal(dir).read("session-1").single().status)
        journal.started("session-1", "call-1")
        assertEquals("started", ToolAttemptJournal(dir).read("session-1").single().status)
        journal.completed("session-1", "call-1", "修改成功")
        assertEquals("修改成功", ToolAttemptJournal(dir).read("session-1").single().output)
        journal.acknowledge("session-1", "call-1")
        assertTrue(journal.read("session-1").isEmpty())
    }

    @Test fun `interrupted side effect is recovered as unknown and never replayed`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val finish = Json.parseToJsonElement("""[{"type":"message","content":[{"type":"output_text","text":"请人工核对修改结果"}]}]""").jsonArray
            server.enqueue(stream(finish))
            val originalCall = buildJsonObject {
                put("type", "function_call")
                put("call_id", "interrupted-1")
                put("name", "write_file")
                put("arguments", """{"path":"target","content":"danger"}""")
            }
            val start = Session(project = "工程", wire = JsonArray(listOf(obj("role" to "user", "content" to "旧任务"), originalCall)))
            val journal = ToolAttemptJournal(Files.createTempDirectory("interrupted").toFile())
            journal.begin(start.id, listOf(ToolCall("interrupted-1", "write_file", obj("path" to "target", "content" to "danger"))))
            journal.started(start.id, "interrupted-1")
            val fs = workspace()
            val runtime = AgentRuntime(
                ResponsesClient(ApiConfig(server.url("/").toString(), "test", "test", allowLoopbackHttp = true)),
                LocalTools(fs, ShellRunner("/bin/sh", fs.root)), { fail("不能请求重放审批") }, journal = journal
            )
            val final = runtime.run(start, "上一轮结果如何") {}
            assertFalse(fs.resolve("target").exists())
            assertTrue(final.messages.any { it.detail.contains("执行状态未知") })
            assertTrue(journal.read(start.id).isEmpty())
            assertTrue(server.takeRequest().body.readUtf8().contains("执行状态未知"))
        }
    }


    @Test fun `section CRUD and session assignment survive persistence`() {
        val dir = Files.createTempDirectory("session-sections").toFile()
        val sections = SessionSectionStore(dir)
        val store = SessionStore(dir)
        val section = sections.create("项目一", "待审查")
        assertEquals(1, sections.list("项目一").size)
        assertTrue(sections.list("项目二").isEmpty())
        val session = Session(project = "项目一", sectionId = section.id, title = "问题分析")
        store.save(session)
        assertEquals(section.id, SessionStore(dir).list().single().sectionId)
        assertEquals(section.id, store.load(session.id).sectionId)
        assertFails { sections.remove(section.id, store.list(includeArchived = true)) }
        sections.rename(section.id, "已分析")
        assertEquals("已分析", SessionSectionStore(dir).list("项目一").single().name)
        store.save(store.load(session.id).copy(sectionId = null))
        sections.remove(section.id, store.list(includeArchived = true))
        assertTrue(sections.list("项目一").isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test fun `section names are unique within each project`() {
        val sections = SessionSectionStore(Files.createTempDirectory("section-names").toFile())
        sections.create("项目一", "重要")
        assertFails { sections.create("项目一", "重要") }
        assertEquals("重要", sections.create("项目二", "重要").name)
    }


    @Test fun `turn change history removes only the selected turn`() {
        val directory = Files.createTempDirectory("turn-history").toFile()
        val store = ChangeStore(directory)
        val first = FileChange("a", null, "第一次", sessionId = "one", turnId = "t1")
        val second = FileChange("a", "第一次", "第二次", sessionId = "one", turnId = "t2")
        val other = FileChange("a", null, "其他会话", sessionId = "two", turnId = "t3")
        store.append("同一项目", listOf(first, second, other))
        store.removeTurn("同一项目", "t2")
        assertEquals(listOf(first, other), ChangeStore(directory).list("同一项目"))
    }

    @Test fun `revert batch refuses a newer human edit and keeps all files unchanged`() {
        val fs = workspace()
        fs.write("a", "初始A")
        fs.write("b", "初始B")
        val changes = listOf(fs.previewWrite("a", "修改A"), fs.previewWrite("b", "修改B"))
        fs.commitAll(changes)
        fs.write("b", "用户的新修改")
        val reverse = changes.map { FileChange(it.path, it.after, it.before) }
        assertFails { fs.commitAll(reverse) }
        assertEquals("修改A", fs.read("a"))
        assertEquals("用户的新修改", fs.read("b"))
    }


    @Test fun `skill catalog finds default and configured project directories`() {
        val fs = workspace()
        fs.write("技能/默认/SKILL.md", "默认技能")
        fs.write("扩展/示例/SKILL.md", "扩展技能")
        val catalog = SkillCatalog(fs)
        assertEquals(listOf("技能/默认/SKILL.md"), catalog.list())
        fs.commit(catalog.previewConfig(listOf("扩展", "扩展")))
        assertEquals(listOf("扩展"), catalog.extraRoots())
        assertEquals(setOf("扩展/示例/SKILL.md", "技能/默认/SKILL.md"), SkillCatalog(fs).list().toSet())
        assertFails { catalog.previewConfig(listOf("../外部")) }
    }

    @Test fun `skill catalog ignores symlinked subdirectories`() {
        val fs = workspace()
        val outside = Files.createTempDirectory("outside-skill")
        Files.createDirectories(outside.resolve("bad"))
        Files.writeString(outside.resolve("bad/SKILL.md"), "不应读取")
        fs.resolve("技能").mkdirs()
        Files.createSymbolicLink(fs.resolve("技能/链接").toPath(), outside)
        assertTrue(SkillCatalog(fs).list().isEmpty())
    }

    @Test fun `change history never leaks across projects and survives restart`() {
        val dir = Files.createTempDirectory("changes").toFile()
        val store = ChangeStore(dir)
        val a = FileChange("同名.txt", null, "项目A")
        val b = FileChange("同名.txt", null, "项目B")
        store.append("项目A", listOf(a))
        store.append("项目B", listOf(b))
        assertEquals(listOf(a), ChangeStore(dir).list("项目A"))
        store.remove("项目B", b)
        assertTrue(store.list("项目B").isEmpty())
        assertEquals(listOf(a), store.list("项目A"))
    }

}
