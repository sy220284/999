package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
class AndroidDebugConnectionTest {
    @Test fun receiptMustMatchOwnedProcessAndLiteralLoopback(){
        assertEquals("ws://127.0.0.1:4321",AndroidDebugConnection.address("{\"pid\":7,\"address\":\"ws://127.0.0.1:4321\"}",7))
        assertFailsWith<IllegalArgumentException>{AndroidDebugConnection.address("{\"pid\":8,\"address\":\"ws://127.0.0.1:4321\"}",7)}
        assertFailsWith<IllegalArgumentException>{AndroidDebugConnection.address("{\"pid\":7,\"address\":\"ws://attacker.invalid:4321\"}",7)}
    }
    @Test fun credentialsStayOutOfDisplayAndArguments(){
        val connection=AndroidDebugConnection("ws://127.0.0.1:4321","a".repeat(43))
        assertFalse(connection.toString().contains("a".repeat(43)))
        assertTrue(connection.document().contains("a".repeat(43)))
        assertFailsWith<IllegalArgumentException>{AndroidDebugConnection("ws://127.0.0.1:4321","bad\r\nheader")}
    }
}
