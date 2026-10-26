package cn.codex.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.codex.core.ShellRunner
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @Test fun chineseNavigationAndFiles() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("把想法，\n写进现实。").fetchSemanticsNodes().isNotEmpty() }
        screenshot("首页")
        rule.onNodeWithContentDescription("打开侧栏").performClick()
        rule.waitForIdle(); screenshot("中文侧栏")
        rule.onNodeWithText("项目文件").performClick()
        val filename = "界面验收-${System.currentTimeMillis()}.md"
        rule.onNodeWithContentDescription("新建文件或目录").performClick()
        rule.onNodeWithText("名称，例如：说明.md").performTextInput(filename)
        rule.onNodeWithText("创建", useUnmergedTree = true).performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText(filename).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(filename).performClick()
        rule.onNodeWithText("UTF-8 文本").performTextInput("中文文件保存验证\n第二行")
        rule.onNodeWithText("保存", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        screenshot("文件编辑")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.waitUntil(10000) { File(context.filesDir, "工作区/默认项目/$filename").readText() == "中文文件保存验证\n第二行" }
    }
    @Test fun localGitCanCommitOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fs = cn.codex.core.Workspace(File(context.cacheDir, "Git验收-${System.currentTimeMillis()}"))
        val tools = GitTools(fs)
        tools.execute(cn.codex.core.ToolCall("init", "git", cn.codex.core.obj("action" to "init")))
        fs.write("说明.md", "安卓本机 Git")
        tools.execute(cn.codex.core.ToolCall("add", "git", cn.codex.core.obj("action" to "add", "value" to ".")))
        tools.execute(cn.codex.core.ToolCall("commit", "git", cn.codex.core.obj("action" to "commit", "value" to "验证本机提交")))
        val history = tools.execute(cn.codex.core.ToolCall("log", "git", cn.codex.core.obj("action" to "log")))
        assertTrue(history.contains("验证本机提交"))
    }
    @Test fun nativeShellRunsAndCancellationStopsGrandchildren() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "命令测试").apply { mkdirs() }
        val runner = ShellRunner("/system/bin/sh", directory, launcher = { NativeShell.start(it, directory) })
        val result = runner.execute("printf '本机执行成功'; exit 7", 10) {}
        assertEquals(7, result.exitCode)
        assertEquals("本机执行成功", result.output)
        val marker = File(directory, "不应生成")
        marker.delete()
        val running = launch(Dispatchers.Default) { runner.execute("(sleep 3; echo bad > 不应生成) & wait", 10) {} }
        delay(300)
        withTimeout(2000) { running.cancelAndJoin() }
        delay(3500)
        assertFalse("取消后孙进程不应继续写文件", marker.exists())
    }
}
