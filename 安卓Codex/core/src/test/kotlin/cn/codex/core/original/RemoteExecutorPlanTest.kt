package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class RemoteExecutorPlanTest {
    @Test fun awsUsesDirectWhileScopedCredentialsUseNoiseAndNoListenerAuth(){
        val aws=RemoteExecutorPlan.build("https://service.invalid","env","phone",RemoteExecutorAuth.AWS,"profile","us-east-1","execute-api")
        assertTrue("direct" in aws.arguments);assertTrue("--aws-sigv4" in aws.arguments);assertFalse("--ws-auth" in aws.arguments);assertTrue("--aws-profile=profile" in aws.arguments)
        val scoped=RemoteExecutorPlan.build("https://service.invalid","env","phone",RemoteExecutorAuth.SCOPED,"","","")
        assertTrue("--environment-id=env" in scoped.arguments);assertTrue("noise" in scoped.arguments);assertTrue("--executor-credential-stdin" in scoped.arguments);assertTrue("--exit-on-stdin-close" in scoped.arguments)
    }
    @Test fun credentialsFollowTheStrictOriginalFrameAndRejectExpiredValues(){
        val bytes=RemoteExecutorPlan.credential("env","secret",101,100);assertEquals(10,bytes.last().toInt())
        val json=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("version","environment_id","token","expires_at"),json.keys)
        assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.credential("env","secret",100,100)}
        assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.credential("env","bad\nheader",101,100)}
    }
    @Test fun remoteEndpointCannotEmbedCredentialsOrUseCleartext(){
        for(url in listOf("http://service.invalid","https://user:password@service.invalid","https://service.invalid?token=x"))assertFailsWith<IllegalArgumentException>{RemoteExecutorPlan.build(url,"env","phone",RemoteExecutorAuth.ACCOUNT,"","","")}
    }
}
