package cn.codex.android

/** 复用原版进程树终止逻辑，但由终端的等待线程独占回收根进程。 */
object TerminalProcessTree {
    init { System.loadLibrary("codex_process") }
    external fun terminate(pid: Int)
}
