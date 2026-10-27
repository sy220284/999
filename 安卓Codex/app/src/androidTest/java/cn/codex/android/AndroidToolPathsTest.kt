package cn.codex.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

/** 设备验收源码；完整测试包必须包含真实 rg，不能以跳过测试代替。 */
class AndroidToolPathsTest {
    @Test fun installedRgAliasExecutesRealPcre2Program() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val alias = File(app.toolPath.substringBefore(':'), "rg")
        assertEquals(File(app.applicationInfo.nativeLibraryDir, "libcodex_rg.so").canonicalPath, alias.canonicalPath)
        val input = File(app.cacheDir, "rg-pcre2-input.txt").apply { writeText("foobar\n") }
        val process = ProcessBuilder(alias.absolutePath, "--pcre2", "(?<=foo)bar", input.absolutePath).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals("foobar", process.inputStream.bufferedReader().readText().trim())
        } finally { process.destroyForcibly(); input.delete() }
    }
}
