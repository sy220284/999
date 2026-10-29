package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class McpLoginOptionsTest {
    @Test fun oneTimeChoicesArePassedLiterallyAndDoNotChangePersistentConfig(){
        val p=McpLoginOptions(listOf("read:files","write:files"),"dcr",240).params("name","thread")
        assertEquals("dcr",p["clientRegistration"]?.jsonPrimitive?.content);assertEquals(240,p["timeoutSecs"]?.jsonPrimitive?.int)
        assertEquals(listOf("read:files","write:files"),p["scopes"]?.jsonArray?.map{it.jsonPrimitive.content})
        assertFalse("config" in p)
    }
    @Test fun invalidRegistrationScopesAndTimeoutAreRejected(){
        assertFailsWith<IllegalArgumentException>{McpLoginOptions(emptyList(),"unknown",30).params("s",null)}
        assertFailsWith<IllegalArgumentException>{McpLoginOptions(listOf("read\nwrite"),"auto",30).params("s",null)}
        assertFailsWith<IllegalArgumentException>{McpLoginOptions(listOf("read","read"),null,0).params("s",null)}
    }
}
