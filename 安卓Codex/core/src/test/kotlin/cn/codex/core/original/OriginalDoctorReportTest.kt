package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class OriginalDoctorReportTest {
    @Test fun readsTheOriginalKeyedCheckMapWithoutTurningFailuresGreen(){
        val report=OriginalDoctorReport.parse("""{"schemaVersion":1,"generatedAt":"now","overallStatus":"fail","codexVersion":"x","checks":{"sandbox.helpers":{"id":"sandbox.helpers","category":"sandbox","status":"fail","summary":"unsupported","details":{},"durationMs":1}}}""")
        assertEquals("fail",report.status);assertEquals("失败",report.checks.single().statusLabel)
        assertTrue(report.checks.single().explanation.contains("同一UID"));assertEquals("unsupported",report.checks.single().summary)
    }
    @Test fun unknownChecksStayVisibleAndUnknownVersionIsRejected(){
        val json="""{"schemaVersion":1,"generatedAt":"now","overallStatus":"warning","codexVersion":"x","checks":{"future":{"id":"future","status":"new","summary":"new check","details":{},"durationMs":1}}}"""
        assertEquals("未知状态（new）",OriginalDoctorReport.parse(json).checks.single().statusLabel)
        assertFailsWith<IllegalArgumentException>{OriginalDoctorReport.parse(json.replace("\"schemaVersion\":1","\"schemaVersion\":9"))}
    }
}
