package cn.codex.core

import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream

interface RunningCommand {
    val output: InputStream
    fun pollExit(): Int?
    fun terminate()
}
/** Linux JVM 验证使用；Android 注入原生进程组实现。 */
private class JvmCommand(shell: String, command: String, directory: File): RunningCommand {
    private val process = ProcessBuilder("setsid", shell, "-c", command).directory(directory).redirectErrorStream(true).start()
    override val output get() = process.inputStream
    override fun pollExit(): Int? = if (process.isAlive) null else process.exitValue()
    override fun terminate() {
        ProcessBuilder("/bin/kill", "-KILL", "--", "-${process.pid()}").redirectErrorStream(true).start().waitFor()
        process.destroyForcibly()
    }
}
class ShellRunner(
    private val shell: String,
    private val directory: File,
    private val outputLimit: Int = 256 * 1024,
    private val launcher: (String) -> RunningCommand = { JvmCommand(shell, it, directory) }
) {
    @Volatile private var process: RunningCommand? = null
    val isRunning get() = process?.pollExit() == null && process != null
    suspend fun execute(command: String, timeoutSeconds: Int = 120, onOutput: (String) -> Unit): ShellResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        check(process == null) { "已有命令正在执行" }
        val child = launcher(command)
        process = child
        val output = StringBuilder()
        var truncated = false
        var exitCode = -1
        try {
            withTimeout(timeoutSeconds.coerceIn(1, 600) * 1000L) {
                coroutineScope {
                    val reader = async(Dispatchers.IO) {
                        child.output.reader().use { r ->
                            val buffer = CharArray(2048)
                            while (true) {
                                ensureActive()
                                val count = r.read(buffer)
                                if (count < 0) break
                                val available = (outputLimit - output.length).coerceAtLeast(0)
                                val kept = String(buffer, 0, count.coerceAtMost(available))
                                output.append(kept)
                                if (count > available) truncated = true
                                if (kept.isNotEmpty()) onOutput(kept)
                            }
                        }
                    }
                    try { while (true) { ensureActive(); val code = child.pollExit(); if (code != null) { exitCode = code; break }; delay(25) }; child.terminate(); reader.await() }
                    finally { child.terminate(); reader.cancel() }
                }
            }
            ShellResult(exitCode, output.toString(), truncated)
        } finally { child.terminate(); process = null }
    }
    fun stop() { process?.terminate() }
}
data class ShellResult(val exitCode: Int, val output: String, val truncated: Boolean)
