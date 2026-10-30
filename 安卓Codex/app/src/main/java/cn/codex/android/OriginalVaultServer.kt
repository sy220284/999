package cn.codex.android

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 私有文件路径的本机 Unix socket。只允许原版进程，不允许其工具子进程通过桥接读取凭证。 */
class OriginalVaultServer(context: Context, scope: CoroutineScope) : Closeable {
    val path = File(context.cacheDir, "v-${UUID.randomUUID().toString().take(12)}.sock").absolutePath
    private val vault = OriginalCredentialVault(context)
    private val bound = LocalSocket().apply { bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) }
    private val server = try { LocalServerSocket(bound.fileDescriptor) } catch (failure: Exception) { bound.close(); File(path).delete(); throw failure }
    private val ready = CountDownLatch(1)
    @Volatile private var enginePid = -1
    private val closed = AtomicBoolean()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()
    private val job = scope.launch(Dispatchers.IO) {
        while (isActive && !closed.get()) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            sockets += socket
            // 顺序处理让同一凭证的刷新、保存、删除保持一致；错误客户端受读超时限制。
            try { socket.use { handle(it) } } catch (_: Exception) { /* 不输出带凭证的异常链 */ }
            finally { sockets -= socket }
        }
    }
    fun authorize(pid: Int) { require(pid > 0); check(enginePid < 0); enginePid = pid; ready.countDown() }
    private fun handle(socket: LocalSocket) {
        socket.soTimeout = 5_000
        val output = DataOutputStream(socket.outputStream)
        val peer = socket.peerCredentials
        if (!ready.await(10, TimeUnit.SECONDS) || peer.uid != Process.myUid() || peer.pid != enginePid) { reply(output, 255, "凭证访问被拒绝"); return }
        val input = DataInputStream(socket.inputStream)
        try {
            require(input.readUnsignedByte() == 1)
            val operation = input.readUnsignedByte()
            val service = field(input, 4096); val account = field(input, 4096); val value = field(input, 1_048_576)
            require(service.isNotEmpty() && account.isNotEmpty())
            when (operation) {
                1 -> { val found = vault.load(service, account); reply(output, if (found == null) 0 else 1, found.orEmpty()) }
                2 -> { vault.save(service, account, value); reply(output, 2) }
                3 -> reply(output, if (vault.delete(service, account)) 3 else 4)
                else -> reply(output, 255, "不支持的凭证操作")
            }
        } catch (_: Exception) { reply(output, 255, "凭证存储操作失败") }
    }
    private fun field(input: DataInputStream, max: Int): String {
        val length = input.readInt(); require(length in 0..max)
        val value = ByteArray(length); input.readFully(value)
        return value.toString(Charsets.UTF_8)
    }
    private fun reply(output: DataOutputStream, status: Int, text: String = "") {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeByte(status); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown(); job.cancel()
        runCatching { server.close() }; runCatching { bound.close() }
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        File(path).delete()
    }
}
