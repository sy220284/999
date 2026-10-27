package cn.codex.android

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.*
import java.io.File
import java.lang.ref.WeakReference

data class FullTerminalState(val session: TerminalSession? = null, val running: Boolean = false, val cwd: String = "", val title: String = "交互终端", val error: String? = null,
    val ctrl: Boolean = false, val alt: Boolean = false, val copyRequest: String? = null, val pasteRequest: Boolean = false)

/** Application 持有终端；页面离开只解除视图，前台服务继续持有真实 PTY。 */
class FullTerminalController(private val context: Context) {
    private val mutable = MutableStateFlow(FullTerminalState())
    val state = mutable.asStateFlow()
    private var view = WeakReference<TerminalView>(null)
    private var ticket: String? = null
    private var aborted = false
    private val app get() = context.applicationContext as CodexApplication
    private val lifecycle get() = app.configurationJournal.lifecycle("交互终端")
    val callbacks = object : TerminalCallbacks() {
        override fun onTextChanged(session: TerminalSession) { if (state.value.session === session) view.get()?.onScreenUpdated() }
        override fun onTitleChanged(session: TerminalSession) { if (state.value.session === session) mutable.update { it.copy(title = session.title ?: "交互终端") } }
        override fun onSessionFinished(session: TerminalSession) {
            if (state.value.session !== session) return
            try { ticket?.let { lifecycle.finish(it, !aborted) } }
            catch (failure: Exception) { mutable.update { it.copy(error = "终端操作记录未能保存，请核查命令结果") } }
            finally { ticket = null; mutable.update { it.copy(running = false, title = "终端已结束 · 退出码 ${session.exitStatus}") }; view.get()?.onScreenUpdated() }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { if (state.value.session === session) mutable.update { it.copy(copyRequest = text) } }
        override fun onPasteTextFromClipboard(session: TerminalSession) { if (state.value.session === session) mutable.update { it.copy(pasteRequest = true) } }
        override fun onBell(session: TerminalSession) { view.get()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        override fun onColorsChanged(session: TerminalSession) { view.get()?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { view.get()?.setTerminalCursorBlinkerState(state, true) }
        override fun onSingleTapUp(event: MotionEvent) { view.get()?.let { it.requestFocus(); context.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }
        override fun onScale(scale: Float): Float { view.get()?.let { val size = (14 * context.resources.displayMetrics.scaledDensity * scale).toInt().coerceIn(12, 48); it.setTextSize(size) }; return 1f }
        override fun readControlKey() = state.value.ctrl
        override fun readAltKey() = state.value.alt
    }
    fun attach(terminal: TerminalView) { view = WeakReference(terminal); state.value.session?.let { terminal.attachSession(it) }; terminal.setTerminalCursorBlinkerRate(if (app.controller.state.value.config.motion) 500 else 0); terminal.setTerminalCursorBlinkerState(true, true) }
    fun detach(expected: TerminalView?) { expected?.setTerminalCursorBlinkerState(false, false); if (view.get() === expected) view.clear() }
    fun start(directory: File) {
        if (state.value.running) return
        if (app.controller.state.value.busy || app.originalCore.state.value.working || app.originalCore.state.value.loading || app.originalCore.state.value.turnId != null || app.originalCore.configurationChanging) { mutable.update { it.copy(error = "请先结束当前任务或配置操作") }; return }
        try {
            require(directory.isDirectory) { "工作目录不存在" }
            val operation = lifecycle.begin(); ticket = operation; aborted = false
            val environment = System.getenv().toMutableMap().apply {
                put("PATH", app.toolPath); put("TERM", "xterm-256color"); put("COLORTERM", "truecolor"); put("LANG", "C.UTF-8")
                put("HOME", File(context.filesDir, "终端主目录").apply { mkdirs() }.absolutePath); put("TMPDIR", context.cacheDir.absolutePath)
                remove("OPENAI_API_KEY"); remove("CODEX_API_KEY"); remove("CODEX_ANDROID_API_KEY"); remove("CODEX_ANDROID_VAULT_SOCKET")
            }
            val session = TerminalSession("/system/bin/sh", directory.canonicalPath, arrayOf("sh", "-i"), environment.map { "${it.key}=${it.value}" }.toTypedArray(), 4000, callbacks)
            mutable.value = FullTerminalState(session = session, running = true, cwd = directory.canonicalPath)
            session.updateSize(80, 24, 0, 0)
        } catch (failure: Exception) {
            aborted = true
            val session = state.value.session
            if (session?.isRunning == true) session.finishIfRunning()
            else { ticket?.let { lifecycle.finish(it, false) }; ticket = null }
            mutable.update { it.copy(running = session?.isRunning == true, error = "终端启动失败：${failure.message.orEmpty()}") }
        }
    }
    fun stop(confirmed: Boolean = true) {
        if (!confirmed) aborted = true
        val session = state.value.session ?: return
        if (session.isRunning) { mutable.update { it.copy(title = "正在关闭终端") }; TerminalProcessTree.terminate(session.pid) }
        else mutable.update { it.copy(session = null, running = false, title = "交互终端") }
    }
    fun send(text: String) { val bytes = text.toByteArray(Charsets.UTF_8); state.value.session?.write(bytes, 0, bytes.size) }
    fun ctrl() { mutable.update { it.copy(ctrl = !it.ctrl) } }
    fun alt() { mutable.update { it.copy(alt = !it.alt) } }
    fun paste() { val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""; state.value.session?.emulator?.paste(text); mutable.update { it.copy(pasteRequest = false) } }
    fun acceptCopy() { state.value.copyRequest?.let { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("终端内容", it)) }; mutable.update { it.copy(copyRequest = null) } }
    fun dismissClipboard() { mutable.update { it.copy(copyRequest = null, pasteRequest = false) } }
}
