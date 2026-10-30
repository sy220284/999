package cn.codex.android

import android.os.ParcelFileDescriptor
import cn.codex.core.RunningCommand
import java.io.File
import java.io.InputStream

/** 独立会话/进程组；停止时杀掉整个命令组，避免 sleep、管道、孙进程遗留。 */
object NativeShell {
    init { System.loadLibrary("codex_process") }
    private external fun spawn(command: String, directory: String): IntArray
    private external fun poll(pid: Int): Int
    private external fun killGroup(pid: Int)
    fun start(command: String, directory: File): RunningCommand {
        val result = spawn(command, directory.absolutePath)
        return object : RunningCommand {
            override val output: InputStream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(result[1]))
            private var exit: Int? = null
            @Synchronized override fun pollExit(): Int? {
                if (exit == null) { val code = poll(result[0]); if (code != -1000) exit = code }
                return exit
            }
            override fun terminate() { killGroup(result[0]) }
        }
    }
}
