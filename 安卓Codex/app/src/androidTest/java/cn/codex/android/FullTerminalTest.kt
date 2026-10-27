package cn.codex.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 原生终端设备测试源码，未执行；不运行模型或账号请求。 */
class FullTerminalTest {
    @Test fun ansiAndUnicodeDirectoryRoundTripUsesPublicFdApis() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val directory = File(app.cacheDir, "终端🙂-${System.nanoTime()}").apply { mkdirs() }
        val finished = CountDownLatch(1)
        var session: TerminalSession? = null
        val callbacks = object : TerminalCallbacks() { override fun onSessionFinished(session: TerminalSession) { finished.countDown() } }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session = TerminalSession("/system/bin/sh", directory.absolutePath,
                    arrayOf("sh", "-c", "printf '\\033[31m中文🙂\\033[0m\\n'; pwd"), arrayOf("PATH=${app.toolPath}", "TERM=xterm-256color"), 1000, callbacks)
                session!!.updateSize(80, 24, 0, 0)
            }
            assertTrue(finished.await(8, TimeUnit.SECONDS))
            val result = requireNotNull(session)
            val text = result.emulator.screen.transcriptText
            assertTrue(text.contains("中文🙂")); assertTrue(text.contains(directory.name)); assertFalse(text.contains("\u001b[31m"))
            assertEquals(0, result.exitStatus)
        } finally { session?.takeIf { it.isRunning }?.finishIfRunning(); directory.deleteRecursively() }
    }
}
