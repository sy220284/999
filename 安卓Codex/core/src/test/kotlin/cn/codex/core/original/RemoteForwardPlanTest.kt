package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class RemoteForwardPlanTest {
    @Test fun forwardingUsesNoiseAndOwnedLiteralLoopbackWithoutCredentialInArguments(){
        val plan=RemoteExecutorPlan.build("https://service.invalid","env","phone",RemoteExecutorAuth.SCOPED,"","","",forward="ws://127.0.0.1:4567")
        assertEquals(listOf("forward","--connect=ws://127.0.0.1:4567"),plan.arguments.takeLast(2))
        assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.build("https://service.invalid","env","",RemoteExecutorAuth.AWS,"","us-east-1","execute-api",forward="ws://127.0.0.1:4567")}
        listOf("ws://localhost:4567","ws://127.0.0.1:4567/path","wss://outside.invalid","ws://key@127.0.0.1:4567").forEach{url->assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.build("https://service.invalid","env","",RemoteExecutorAuth.ACCOUNT,"","","",forward=url)}}
    }
    @Test fun executionOptionsAreValidatedBeforeStarting(){
        assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.build("https://service.invalid","env","",RemoteExecutorAuth.ACCOUNT,"","","",concurrent=0)}
        val plan=RemoteExecutorPlan.build("https://service.invalid","env","",RemoteExecutorAuth.ACCOUNT,"","","",concurrent=4,strict=true)
        assertTrue("--concurrent-requests=4" in plan.arguments);assertTrue("--strict-config" in plan.arguments)
    }
}
