package cn.codex.android

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 从安装时提取的 nativeLibraryDir 执行 PIE；不从可写项目目录执行下载代码。 */
class NativeCoreProcess(executable: File, arguments: List<String>, environment: Map<String, String>, directory: File) : Closeable {
    private fun utf8(value: String): ByteArray { require('\u0000' !in value) { "进程参数不能包含空字符" }; return value.toByteArray(Charsets.UTF_8) }
    private val handles = NativeCoreBridge.spawn((listOf(executable.absolutePath) + arguments).map(::utf8).toTypedArray(), environment.map { utf8("${it.key}=${it.value}") }.toTypedArray(), utf8(directory.absolutePath))
    val pid = handles[0]
    val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(handles[2]))
    val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(handles[1]))
    val errors = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(handles[3]))
    private val closed = AtomicBoolean()
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativeCoreBridge.terminate(pid)
        runCatching { output.close() }; runCatching { input.close() }; runCatching { errors.close() }
    }
}

private object NativeCoreBridge {
    init { System.loadLibrary("codex_process") }
    external fun spawn(argv: Array<ByteArray>, env: Array<ByteArray>, cwd: ByteArray): IntArray
    external fun terminate(pid: Int)
}
