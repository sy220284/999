package cn.codex.core.original

import org.junit.Test
import kotlin.test.*
class OriginalWebRtcPolicyTest {
    @Test fun audioSdpIsPreservedWithoutLoggingOrRewritingCredentials() {
        val value="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-pwd:secret\r\n"
        assertEquals(value,OriginalWebRtcPolicy.sdp(value))
    }
    @Test fun oversizedOrTooManyCandidateAnswersAreRejectedBeforeNativeAdmission() {
        assertFailsWith<IllegalArgumentException>{OriginalWebRtcPolicy.sdp("v=0\nm=audio 9 RTP/AVP 0\n"+"x".repeat(65536))}
        assertFailsWith<IllegalArgumentException>{OriginalWebRtcPolicy.sdp("v=0\nm=audio 9 RTP/AVP 0\n"+"a=candidate:x\n".repeat(33))}
        assertFailsWith<IllegalArgumentException>{OriginalWebRtcPolicy.sdp("v=0\nm=video 9 RTP/AVP 0\n")}
    }
    @Test fun permissionGrantCannotAuthorizeAnotherThreadOrBackgroundRecording() {
        assertTrue(OriginalRealtimePermission.allows("t","t",true))
        assertFalse(OriginalRealtimePermission.allows("old","new",true))
        assertFalse(OriginalRealtimePermission.allows(null,"new",true))
        assertFalse(OriginalRealtimePermission.allows("t","t",false))
    }

}
