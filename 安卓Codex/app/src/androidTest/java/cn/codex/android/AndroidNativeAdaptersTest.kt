package cn.codex.android

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.cert.CertificateFactory

/** 仅测试源码：不弹认证、不连接网络，不代表TEE或媒体已经验收。 */
class AndroidNativeAdaptersTest {
    @Test fun pointEncodingIsFixedWidthAndPreservesBothCoordinates() {
        val generator=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}
        val key=generator.generateKeyPair().public as ECPublicKey
        val point=OriginalAndroidVerification.publicPoint(key)
        assertEquals(65,point.size);assertEquals(4,point[0].toInt())
        assertEquals(key.w.affineX,java.math.BigInteger(1,point.copyOfRange(1,33)))
        assertEquals(key.w.affineY,java.math.BigInteger(1,point.copyOfRange(33,65)))
    }
    @Test fun trustBundleContainsParseableAndroidCertificates() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=java.io.File(context.cacheDir,"trust-test-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        try{val bundle=AndroidTrustBundle.write(directory)
            val certificates=bundle.inputStream().use{CertificateFactory.getInstance("X.509").generateCertificates(it)}
            assertTrue(certificates.isNotEmpty())
            val environment=AndroidTrustBundle.environment(bundle)
            assertEquals(bundle.absolutePath,environment["GIT_SSL_CAINFO"])
            assertFalse(environment.containsKey("GIT_SSL_NO_VERIFY"))
        }finally{directory.deleteRecursively()}
    }
    @Test fun svgPreviewRejectsActiveOrRecursiveRenderContent() {
        assertTrue(OriginalSvgPreviewPolicy.allowed("""<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>"""))
        assertFalse(OriginalSvgPreviewPolicy.allowed("""<svg><foreignObject><iframe/></foreignObject></svg>"""))
        assertFalse(OriginalSvgPreviewPolicy.allowed("""<svg><use href="#self" id="self"/></svg>"""))
        assertFalse(OriginalSvgPreviewPolicy.allowed("""<!DOCTYPE svg SYSTEM "file:///private"><svg/>"""))
        assertFalse(OriginalSvgPreviewPolicy.allowed("<svg>"+"<g>".repeat(20)+"</g>".repeat(20)+"</svg>"))
    }

    @Test fun reusedProtocolIdStartsWithANewApprovalUiIdentity() {
        val first=cn.codex.core.original.ServerMessage.Request(kotlinx.serialization.json.JsonPrimitive("same"),"mcpServer/elicitation/request",kotlinx.serialization.json.buildJsonObject{})
        val second=first.copy()
        assertEquals(first,second)
        assertEquals(OriginalRequestKey(first),OriginalRequestKey(first))
        assertNotEquals(OriginalRequestKey(first),OriginalRequestKey(second))
    }

}
