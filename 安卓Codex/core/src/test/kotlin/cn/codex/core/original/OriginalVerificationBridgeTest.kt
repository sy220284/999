package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.util.Base64

class OriginalVerificationBridgeTest {
    private val identity="com.openai.codex.user-verification.plugin-service.v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    @Test fun namespaceRequiresTheExactHashedAccountScope() {
        assertEquals(identity,OriginalVerificationBridgeContract.namespace(identity))
        assertFailsWith<IllegalArgumentException>{OriginalVerificationBridgeContract.namespace("other."+identity)}
        assertFailsWith<IllegalArgumentException>{OriginalVerificationBridgeContract.namespace(identity+"=")}
    }
    @Test fun unknownFieldsAndUnknownOperationsAreRejected() {
        assertFailsWith<IllegalArgumentException>{OriginalVerificationBridgeContract.request(buildJsonObject{put("operation","exportPrivateKey")})}
        assertFailsWith<IllegalArgumentException>{OriginalVerificationBridgeContract.request(buildJsonObject{put("operation","status");put("namespace",identity);put("alias","arbitrary")})}
    }
    @Test fun verifyValidatesTheExactChallengeAndDisplayContext() {
        val valid=buildJsonObject{put("operation","verify");put("namespace",identity);put("challenge","AQ");put("title","确认签名");put("description","")}
        assertEquals(valid,OriginalVerificationBridgeContract.request(valid))
        assertFailsWith<IllegalArgumentException>{OriginalVerificationBridgeContract.request(JsonObject(valid+("challenge" to JsonPrimitive("AQ=="))))}
    }
}
