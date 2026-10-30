package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class OriginalCliCommandsTest {
    @Test fun cloudPromptStaysOneArgumentAndDoesNotBecomeShellCode() {
        val prompt="修复代码; $(echo secret)\n第二行"
        val plan=OriginalCliCommands.cloudCreate("env-1",prompt,"feature/a",4)
        assertEquals(prompt,plan.arguments.last());assertEquals("--",plan.arguments[plan.arguments.lastIndex-1])
        assertTrue(plan.mutating)
    }
    @Test fun attemptsAndIdentitiesFollowTheFixedSourceContract() {
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.cloudCreate("e","p",null,5)}
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.cloudStatus("--help")}
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.cloudDiff("task",0)}
        assertEquals(listOf("cloud","diff","task","--attempt","2"),OriginalCliCommands.cloudDiff("task",2).arguments)
    }
    @Test fun applyCarriesTheExactReviewedDiffForTheHostToFreeze() {
        val diff="diff --git a/a b/a\n"
        val plan=OriginalCliCommands.cloudApply("t",1,diff)
        assertEquals(diff,plan.expectedDiff);assertTrue(plan.mutating)
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.cloudApply("t",1,"")}
    }
    @Test fun migrationInspectionAndPublishingAreDistinctOperations() {
        assertFalse(OriginalCliCommands.rollouts(false).mutating)
        assertFalse("--apply" in OriginalCliCommands.rollouts(false).arguments)
        assertTrue("--apply" in OriginalCliCommands.rollouts(true).arguments)
        assertEquals("y\n",OriginalCliCommands.nfsMigration().input)
        assertTrue(OriginalCliCommands.nfsMigration().mutating)
    }
    @Test fun policyCheckOnlyEvaluatesExplicitArgumentTokens() {
        val p=OriginalCliCommands.execPolicy(listOf("rules/local.rules"),listOf("git","push","--force"),true)
        assertFalse(p.mutating);assertEquals(listOf("git","push","--force"),p.arguments.takeLast(3))
        assertTrue("--resolve-host-executables" in p.arguments)
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.execPolicy(emptyList(),listOf("git"),false)}
    }

    @Test fun unixMcpRelayKeepsSocketPathsWithSpacesAsOneArgument() {
        val socket="/data/user/0/app/files/my service.sock"
        assertEquals(listOf("stdio-to-uds",socket),OriginalCliCommands.stdioToUds(socket))
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.stdioToUds("relative.sock")}
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.stdioToUds("/socket\n--help")}
    }

    @Test fun latestApplyFreezesReviewedBytesAndPreviewDoesNotWrite(){
        val preview=OriginalCliCommands.latestDiff("task")
        assertFalse(preview.mutating);assertEquals(listOf("apply","--preview","--","task"),preview.arguments)
        val apply=OriginalCliCommands.latestApply("task","diff\n")
        assertTrue(apply.mutating);assertEquals("diff\n",apply.expectedDiff)
    }
    @Test fun diagnosticMessageCannotBecomeOptionsOrShellCode(){
        val message="--help; $(echo token)"
        val plan=OriginalCliCommands.debugMessage(message)
        assertEquals(listOf("--",message),plan.arguments.takeLast(2));assertTrue(plan.mutating)
        assertFalse(OriginalCliCommands.promptInput(message,emptyList()).mutating)
    }
    @Test fun schemaExportsUseFreshHostOwnedPathsAndInternalSchemaRejectsExperimental(){
        val plan=OriginalCliCommands.schema("generate-ts",true)
        assertTrue("--experimental" in plan.arguments);assertEquals("@ANDROID_EXPORT@",plan.arguments[3])
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.schema("generate-internal-json-schema",true)}
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.schema("daemon",false)}
    }
    @Test fun humanDoctorFlagsDoNotAccidentallyMixWithJson(){
        assertFailsWith<IllegalArgumentException>{OriginalCliCommands.doctor(json=true,summary=true)}
        assertEquals(setOf("doctor","--summary","--all","--ascii","--feedback","--no-color"),OriginalCliCommands.doctor(false,true,true,true,true).arguments.toSet())
    }

}
