package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class StandaloneCliTest {
    @Test fun execUsesLiteralOptionsStdinAndIndependentLastMessageExport(){
        val plan=OriginalStandaloneCommands.exec("do work",buildJsonObject{put("json",true);put("ephemeral",true);put("profile","dev");put("output-last-message",true);put("add-dir",buildJsonArray{add("other folder")})},"resume","session")
        assertEquals(listOf("resume","session","--","-"),plan.arguments.takeLast(4));assertEquals("do work",plan.input)
        assertTrue("--profile=dev" in plan.arguments);assertTrue("--add-dir=other folder" in plan.arguments);assertTrue(plan.arguments.any{it.startsWith("--output-last-message=@ANDROID_EXPORT@/")})
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.exec("x",buildJsonObject{put("unknown-command",true)})}
    }
    @Test fun conflictingReviewTargetsAndInvalidOptionsAreRejected(){
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.exec("x",buildJsonObject{put("approve-for-me",true);put("sandbox","read-only")})}
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.exec("x",buildJsonObject{},"fork","")}
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.exec("x",buildJsonObject{put("output-schema","schema.json")},"review-uncommitted")}
    }
    @Test fun loginNeverPutsTokenInArgumentsOrDiagnosticString(){
        val plan=OriginalStandaloneCommands.loginToken("private-token",false)
        assertEquals(listOf("login","--with-access-token"),plan.arguments);assertFalse(plan.arguments.any{it.contains("private-token")});assertFalse(plan.toString().contains("private-token"));assertEquals("private-token\n",plan.input)
    }
    @Test fun deviceCodeCustomIssuerMustBeHttpsWithoutEmbeddedCredentials(){
        val plan=OriginalStandaloneCommands.loginDevice("https://issuer.invalid","custom-app")
        assertTrue("--experimental_issuer=https://issuer.invalid" in plan.arguments);assertTrue("--experimental_client-id=custom-app" in plan.arguments)
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.loginDevice("http://issuer.invalid","")}
        assertFailsWith<IllegalArgumentException>{OriginalStandaloneCommands.loginDevice("https://user:secret@issuer.invalid","")}
    }
}
