package cn.codex.core.original

import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** 每条流独立解码跨帧 UTF-8，不把截断的汉字替换成乱码。 */
class CommandUtf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var tail = byteArrayOf()
    fun append(bytes: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(tail + bytes)
        val output = CharBuffer.allocate(input.remaining() + 8)
        decoder.decode(input, output, end)
        tail = ByteArray(input.remaining()).also { input.get(it) }
        if (end) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}
data class OriginalCommandState(val connected: Boolean = false, val running: Boolean = false, val controlBusy: Boolean = false,
    val processId: String? = null, val tty: Boolean = false, val stdinClosed: Boolean = false, val output: String = "", val error: String? = null, val status: String = "尚未运行命令")
class OriginalCommandController(private val scope: CoroutineScope, private val busyElsewhere: () -> Boolean, private val lifecycle: OriginalMutationLifecycle = OriginalMutationLifecycle.None) {
    private val mutable = MutableStateFlow(OriginalCommandState())
    val state = mutable.asStateFlow()
    private var client: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    private var control: Job? = null
    private var stdout = CommandUtf8Stream(); private var stderr = CommandUtf8Stream()
    fun bind(api: OriginalCodexClient?) { generation++; job?.cancel(); control?.cancel(); client = api; mutable.value = if (api == null) state.value.copy(connected = false, running = false, controlBusy = false, status = "连接已结束，请核查最后命令结果") else OriginalCommandState(connected = true) }
    private fun append(text: String) { mutable.update { it.copy(output = (it.output + text).takeLast(1_048_576)) } }
    fun run(command: String, cwd: String, tty: Boolean, timeoutMs: Long, rows: Int = 24, cols: Int = 80) {
        if (state.value.running) return
        if (busyElsewhere()) { mutable.update { it.copy(error = "请先结束其他任务、终端或仓库操作") }; return }
        val api = client ?: return
        require(command.isNotBlank() && timeoutMs > 0 && timeoutMs <= Long.MAX_VALUE - 10_000 && rows > 0 && cols > 0)
        val ticket = try { lifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; return }
        val stamp = generation
        val process = UUID.randomUUID().toString()
        stdout = CommandUtf8Stream(); stderr = CommandUtf8Stream()
        mutable.value = OriginalCommandState(connected = true, running = true, processId = process, tty = tty, status = "命令运行中")
        job = scope.launch {
            var confirmed = false
            try {
                val result = api.exec(process, listOf("/system/bin/sh", "-c", command), cwd, tty, timeoutMs, rows, cols).jsonObject
                if (stamp == generation) {
                    append(stdout.append(byteArrayOf(), true) + stderr.append(byteArrayOf(), true))
                    append(result.string("stdout") + result.string("stderr"))
                    mutable.update { it.copy(status = "命令已结束 · 退出码 ${result["exitCode"]}") }; confirmed = true
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (stamp == generation) mutable.update { it.copy(error = "命令响应超时，正在终止本次连接；结果仍需核查", status = "命令结果待核查") }
                withContext(NonCancellable) { try { withTimeout(5000) { api.terminateCommand(process) } } finally { api.connection.close() } }
            } catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = failure.message, status = "命令结果待核查") } }
            finally {
                try { lifecycle.finish(ticket, confirmed && stamp == generation) }
                catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = "操作记录保存失败，请核查命令结果") } }
                finally { if (stamp == generation) mutable.update { it.copy(running = false, controlBusy = false) } }
            }
        }
    }
    private fun control(action: suspend (OriginalCodexClient, String) -> Unit) {
        val api = client ?: return; val process = state.value.processId ?: return
        if (!state.value.running || state.value.controlBusy) return
        val stamp = generation
        mutable.update { it.copy(controlBusy = true, error = null) }
        control = scope.launch {
            try { action(api, process) }
            catch (failure: Exception) { if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure; if (stamp == generation) mutable.update { it.copy(error = failure.message ?: "命令控制结果未确认，不会自动重发") } }
            finally { if (stamp == generation) mutable.update { it.copy(controlBusy = false) } }
        }
    }
    fun send(text: String, closeStdin: Boolean = false) = control { api, id ->
        check(!state.value.stdinClosed) { "标准输入已关闭" }
        api.writeCommand(id, text.toByteArray(Charsets.UTF_8), closeStdin)
        if (state.value.processId == id && closeStdin) mutable.update { it.copy(stdinClosed = true) }
    }
    fun resize(rows: Int, cols: Int) = control { api, id -> require(state.value.tty && rows > 0 && cols > 0); api.resizeCommand(id, rows, cols) }
    fun stop() = control { api, id -> api.terminateCommand(id); if (state.value.processId == id) mutable.update { it.copy(status = "已请求终止，等待命令结束") } }
    fun output(params: JsonObject) {
        if (!state.value.running || params.string("processId") != state.value.processId) return
        val bytes = Base64.getDecoder().decode(params.string("deltaBase64"))
        val text = (if (params.string("stream") == "stderr") stderr else stdout).append(bytes)
        append(text)
        if (params["capReached"]?.jsonPrimitive?.booleanOrNull == true) mutable.update { it.copy(status = "输出达到服务端上限，命令仍可能运行") }
    }
}
