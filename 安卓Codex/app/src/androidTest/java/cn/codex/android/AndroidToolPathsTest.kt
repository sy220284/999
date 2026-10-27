package cn.codex.android

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** 测试源码，当前未运行；第二项需要后续实际构建并打包的 Git。 */
class AndroidToolPathsTest {
    @Test fun installedAliasesFollowPackageUpdatesAndRemoveMissingPrograms() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val root = File(app.cacheDir, "aliases-${System.nanoTime()}").apply { mkdirs() }
        val libraries = File(root, "native").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply { nativeLibraryDir = libraries.absolutePath }
        }
        try {
            val binary = File(libraries, "libcodex_git.so").apply { writeText("fixture; not executable code"); setExecutable(true) }
            AndroidToolPaths.install(context)
            val alias = File(AndroidToolPaths.directory(context), "git")
            assertEquals(binary.absolutePath, Os.readlink(alias.absolutePath))
            assertEquals(binary.absolutePath, Os.readlink(File(alias.parentFile, "git-upload-pack").absolutePath))
            assertTrue(binary.delete())
            AndroidToolPaths.install(context)
            assertFalse(File(alias.parentFile, "git").exists())
            try { Os.readlink(alias.absolutePath); fail("失效别名未删除") } catch (_: android.system.ErrnoException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeGitSupportsUnicodeHistoryAndLinkedWorktrees() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val binary = File(app.applicationInfo.nativeLibraryDir, "libcodex_git.so")
        assertTrue("先构建并安装原生 Git 后运行本项", binary.canExecute())
        val root = File(app.cacheDir, "git-中文🙂-${System.nanoTime()}").apply { mkdirs() }
        val repo = File(root, "主仓库").apply { mkdirs() }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf(binary.absolutePath) + args).directory(repo).redirectErrorStream(true).apply {
                environment().putAll(AndroidToolPaths.environment(app)); environment()["HOME"] = root.absolutePath
                environment()["PATH"] = app.toolPath
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }.start()
            try {
                assertTrue("Git 命令超时", process.waitFor(10, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue()); return output
            } finally { process.destroyForcibly() }
        }
        try {
            git("init", "-b", "main")
            File(repo, "中文.txt").writeText("第一行🙂\n")
            git("add", ".")
            git("-c", "user.name=测试", "-c", "user.email=test@localhost", "commit", "-m", "中文首次提交")
            assertTrue(git("log", "-1", "--format=%s").contains("中文首次提交"))
            File(repo, "中文.txt").appendText("第二行\n")
            assertTrue(git("diff", "--no-ext-diff").contains("第二行"))
            git("worktree", "add", "-b", "mobile-work", File(root, "子工作树").absolutePath)
            assertTrue(git("worktree", "list", "--porcelain").contains("mobile-work"))
            git("worktree", "remove", File(root, "子工作树").absolutePath)
        } finally { root.deleteRecursively() }
    }
}
