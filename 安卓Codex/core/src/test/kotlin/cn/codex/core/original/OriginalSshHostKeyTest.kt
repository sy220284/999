package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
class OriginalSshHostKeyTest {
    private fun key(type:String="ssh-ed25519",size:Int=32):String {
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeInt(type.length);it.write(type.toByteArray());it.writeInt(size);it.write(ByteArray(size))}
        return "$type ${Base64.getEncoder().encodeToString(bytes.toByteArray())}"
    }
    @Test fun literalHostAndPublicKeyProduceTheStoredFingerprint() {
        val value=OriginalSshHostKey.parse("Example.COM",key())
        assertEquals("example.com",value.host);assertTrue(value.fingerprint.startsWith("SHA256:"));assertEquals(3,value.line.split(' ').size)
    }
    @Test fun wildcardShellAndMalformedWireKeysAreRejected() {
        for(host in listOf("*","-oProxyCommand=bad","host\nother","a,b","[host]:99999"))assertFailsWith<IllegalArgumentException>{OriginalSshHostKey.parse(host,key())}
        assertFailsWith<IllegalArgumentException>{OriginalSshHostKey.parse("host",key(size=31))}
        assertFailsWith<IllegalArgumentException>{OriginalSshHostKey.parse("host",key().replaceFirst("ssh-ed25519 ","ssh-rsa "))}
    }
    @Test fun nondefaultPortsStayPartOfHostIdentity() {
        assertEquals("[example.com]:2222",OriginalSshHostKey.parse("[EXAMPLE.COM]:2222",key()).host)
        assertEquals("example.com",OriginalSshHostKey.parse("[example.com]:22",key()).host)
    }
}
