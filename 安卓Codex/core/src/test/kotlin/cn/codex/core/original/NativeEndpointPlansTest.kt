package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class NativeEndpointPlansTest {
    @Test fun tunnelTrustUsesAnExactHttpsOriginAndAnExplicitTargetPort(){
        assertEquals("https://example.com",NativeEndpointPlans.origin("https://EXAMPLE.com:443/"))
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.origin("https://example.com/path")}
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.origin("https://user@example.com")}
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.tunnel("https://example.com","host",0,"/origins")}
        val p=NativeEndpointPlans.tunnel("https://example.com","host:22",0,"/origins")
        assertTrue("--auth-token-updates-stdin" in p.arguments);assertTrue("127.0.0.1:0" in p.arguments)
    }
    @Test fun protectedListenersNeverPutClientTokensInArguments(){
        val p=NativeEndpointPlans.executor("a".repeat(64),0)
        assertTrue("--ws-token-sha256" in p.arguments);assertTrue("capability-token" in p.arguments)
        assertTrue("--exit-on-stdin-close" in p.arguments)
        val proxy=NativeEndpointPlans.proxy("https://service.example/v1/responses",0,"/receipt","/client-token")
        assertTrue("--client-token-file" in proxy.arguments)
        assertTrue("--exit-on-stdin-close" in proxy.arguments)
    }
    @Test fun onlyTheExpectedLoopbackReadinessLineIsAccepted(){
        assertEquals("ws://127.0.0.1:1234",NativeEndpointPlans.readyLine(NativeEndpointKind.EXECUTOR,"ws://127.0.0.1:1234"))
        assertNull(NativeEndpointPlans.readyLine(NativeEndpointKind.EXECUTOR,"ws://0.0.0.0:1234"))
        assertNull(NativeEndpointPlans.readyLine(NativeEndpointKind.TUNNEL,"LISTENING 127.0.0.1:0"))
        assertEquals("127.0.0.1:22",NativeEndpointPlans.readyLine(NativeEndpointKind.TUNNEL,"LISTENING 127.0.0.1:22"))
    }
    @Test fun connectMetadataCannotOverrideAuthOrForwardingHeaders(){
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.metadata("[[\"authorization\",\"secret\"]]")}
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.metadata("[[\"x-forwarded-for\",\"1\"]]")}
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.metadata("[[\"x-project\",\"a\"],[\"X-Project\",\"b\"]]")}
        assertEquals("[[\"x-project\",\"name\"]]",NativeEndpointPlans.metadata("[[\"x-project\",\"name\"]]"))
    }
    @Test fun staleGenerationCannotChangeAReplacementService(){
        val model=NativeEndpointGeneration();model.start("old");model.stop("old");assertFalse(model.ready("old"));model.finish("old");model.start("new")
        assertFalse(model.finish("old"));assertFalse(model.ready("old"));assertTrue(model.ready("new"));assertTrue(model.running)
        assertTrue(model.finish("new"));assertFalse(model.running)
    }
    @Test fun disabledRemoteControlUsesTheOriginalScopedEnvironmentContract(){
        val plan=NativeEndpointPlans.daemon("a".repeat(64),0,false,true)
        assertEquals("1",plan.environment["CODEX_INTERNAL_APP_SERVER_REMOTE_CONTROL_DISABLED"])
        assertFalse("--remote-control-disabled" in plan.arguments)
        assertFailsWith<IllegalArgumentException>{NativeEndpointPlans.daemon("a".repeat(64),0,true,true)}
    }

}
