package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class AndroidCommandPolicyDocumentTest {
    @Test fun policyContainsOnlyHostPathsAndVersion(){
        val document=AndroidCommandPolicyDocument.create("/private/project","/installed/libcodex_sandbox.so")
        assertEquals(setOf("version","project","program"),document.keys)
        assertEquals(1,document["version"]?.jsonPrimitive?.int)
        assertFailsWith<IllegalArgumentException>{AndroidCommandPolicyDocument.create("relative","/installed/libcodex_sandbox.so")}
        assertFailsWith<IllegalArgumentException>{AndroidCommandPolicyDocument.create("/","/installed/libcodex_sandbox.so")}
    }
}
