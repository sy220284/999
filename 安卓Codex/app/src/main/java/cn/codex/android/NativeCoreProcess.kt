package cn.codex.android

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

interface OwnedNativeProcess : Closeable {
    val pid:Int
    val input:java.io.InputStream
    val output:java.io.OutputStream
    val errors:java.io.InputStream
    fun pollExit():Int?
    fun requestTermination()
}

/** 从安装时提取的 nativeLibraryDir 执行 PIE；不从可写项目目录执行下载代码。 */
class NativeCoreProcess(executable: File, arguments: List<String>, environment: Map<String, String>, directory: File) : OwnedNativeProcess {
    private fun utf8(value: String): ByteArray { require('\u0000' !in value) { "进程参数不能包含空字符" }; return value.toByteArray(Charsets.UTF_8) }
    private val handles = NativeCoreBridge.spawn((listOf(executable.absolutePath) + arguments).map(::utf8).toTypedArray(), environment.map { utf8("${it.key}=${it.value}") }.toTypedArray(), utf8(directory.absolutePath))
    override val pid = handles[0]
    override val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(handles[2]))
    override val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(handles[1]))
    override val errors = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(handles[3]))
    private val closed = AtomicBoolean()
    @Synchronized override fun pollExit():Int? {
        check(!closed.get()){ "进程资源已关闭" }
        return NativeCoreBridge.poll(pid).takeUnless{it==Int.MIN_VALUE}
    }
    @Synchronized override fun requestTermination(){if(!closed.get())NativeCoreBridge.requestTermination(pid)}
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativeCoreBridge.terminate(pid)
        runCatching { output.close() }; runCatching { input.close() }; runCatching { errors.close() }
    }
}

private object NativeCoreBridge {
    init { System.loadLibrary("codex_process") }
    external fun spawn(argv: Array<ByteArray>, env: Array<ByteArray>, cwd: ByteArray): IntArray
    external fun requestTermination(pid:Int)
    external fun poll(pid:Int):Int
    external fun terminate(pid: Int)
}
