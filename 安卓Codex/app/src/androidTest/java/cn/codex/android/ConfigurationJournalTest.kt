package cn.codex.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** 设备测试源码，未执行；覆盖重启后只核查、不重发的记录语义。 */
class ConfigurationJournalTest {
    @Test fun interruptedRecordSurvivesRecreationAndExplicitReviewClearsIt() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val directory = File(app.cacheDir, "journal-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val file = File(directory, "原版配置操作.json")
        try {
            file.writeText("""[{"id":"interrupted","kind":"服务配置","startedAt":1}]""")
            val restored = ConfigurationJournal(context)
            assertEquals("interrupted", restored.records.value.single().id)
            assertNull(restored.active.value)
            assertTrue(restored.records.value.single().kind.contains("配置"))
            restored.confirmReviewed()
            assertTrue(ConfigurationJournal(context).records.value.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
