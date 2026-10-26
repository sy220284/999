package cn.codex.android

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Android PTY 后端：标准输入/输出共用一个伪终端，支持交互式 shell、尺寸变化和会话组终止。
 * 仅能访问应用 UID 有权限的路径，不能当作桌面沙箱或完整 Linux 环境。
 */
object NativePty {
    init { System.loadLibrary("codex_process") }
    private external fun openSession(directory: String, rows: Int, columns: Int): IntArray
    private external fun resizeFd(fd: Int, pid: Int, rows: Int, columns: Int)
    private external fun terminateGroup(pid: Int)

    class Session internal constructor(private val pid: Int, private val descriptor: ParcelFileDescriptor) : AutoCloseable {
        val input: InputStream = FileInputStream(descriptor.fileDescriptor)
        val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)
        @Volatile private var closed = false
        fun send(text: String) {
            check(!closed) { "终端已关闭" }
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }
        fun resize(rows: Int, columns: Int) {
            require(rows in 2..300 && columns in 10..500) { "终端窗口尺寸无效" }
            check(!closed) { "终端已关闭" }
            resizeFd(descriptor.fd, pid, rows, columns)
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            terminateGroup(pid)
            descriptor.close()
        }
    }
    fun start(directory: File, rows: Int = 24, columns: Int = 80): Session {
        require(directory.isDirectory) { "工作目录不存在" }
        require(rows in 2..300 && columns in 10..500)
        val result = openSession(directory.canonicalPath, rows, columns)
        require(result.size == 2) { "无法启动 PTY" }
        return Session(result[0], ParcelFileDescriptor.adoptFd(result[1]))
    }
}
