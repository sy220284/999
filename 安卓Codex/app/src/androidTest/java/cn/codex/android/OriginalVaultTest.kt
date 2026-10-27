package cn.codex.android

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** 仅提交源码，本阶段不执行设备测试。 */
class OriginalVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedCredentialSurvivesReopenAndDeleteDoesNotAffectAnotherAccount() {
        val vault = OriginalCredentialVault(context)
        val service = "test-${UUID.randomUUID()}"; val secret = "test-secret-${UUID.randomUUID()}"
        try {
            assertNull(vault.load(service, "a"))
            vault.save(service, "a", secret); vault.save(service, "b", "other")
            assertEquals(secret, OriginalCredentialVault(context).load(service, "a"))
            assertTrue(File(context.noBackupFilesDir, "原版凭证").listFiles().orEmpty().all { !it.readText().contains(secret) })
            assertTrue(vault.delete(service, "a")); assertFalse(vault.delete(service, "a"))
            assertEquals("other", vault.load(service, "b"))
        } finally { vault.delete(service, "a"); vault.delete(service, "b") }
    }
    @Test fun vaultSocketChecksExactPidAndPersistsUtf8ProtocolValues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            OriginalVaultServer(context, scope).use { denied ->
                denied.authorize(Process.myPid() + 100_000)
                LocalSocket().use { socket ->
                    socket.soTimeout = 2000; socket.connect(LocalSocketAddress(denied.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    assertEquals(255, DataInputStream(socket.inputStream).readUnsignedByte())
                }
            }
            OriginalVaultServer(context, scope).use { server ->
                server.authorize(Process.myPid())
                val service = "wire-${UUID.randomUUID()}"
                assertEquals(2, request(server.path, 2, service, "a", "中文😀").first)
                assertEquals(1 to "中文😀", request(server.path, 1, service, "a"))
                assertEquals(3, request(server.path, 3, service, "a").first)
                assertEquals(0, request(server.path, 1, service, "a").first)
            }
        } finally { scope.cancel() }
    }
    private fun request(path: String, operation: Int, service: String, account: String, value: String = ""): Pair<Int, String> = LocalSocket().use { socket ->
        socket.soTimeout = 3000; socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        val output = DataOutputStream(socket.outputStream); output.writeByte(1); output.writeByte(operation)
        listOf(service, account, value).forEach { field -> val bytes = field.toByteArray(); output.writeInt(bytes.size); output.write(bytes) }; output.flush()
        val input = DataInputStream(socket.inputStream); val status = input.readUnsignedByte(); val length = input.readInt()
        require(length in 0..1_048_576); val bytes = ByteArray(length); input.readFully(bytes)
        status to bytes.toString(Charsets.UTF_8)
    }
}
