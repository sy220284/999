package cn.codex.android

import android.content.Context
import cn.codex.core.ToolCall
import cn.codex.core.Workspace
import cn.codex.core.obj
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class GitNetworkState(val running: Boolean = false, val output: String = "", val error: String? = null)
class GitNetworkController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(GitNetworkState())
    val state = mutable.asStateFlow()
    private val app get() = context.applicationContext as CodexApplication
    private var job: Job? = null
    fun execute(directory: File, action: String, value: String, remote: String, branch: String, expected: String) {
        if (state.value.running) return
        if (app.controller.state.value.busy || app.originalCore.state.value.working || app.originalCore.state.value.turnId != null || app.originalCore.state.value.loading || app.originalCore.configurationChanging) { mutable.update { it.copy(error = "请先结束其他任务、终端或配置操作") }; return }
        val lifecycle = app.configurationJournal.lifecycle("Git 仓库操作")
        val ticket = try { lifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = failure.message) }; return }
        mutable.update { it.copy(running = true, error = null) }
        job = scope.launch {
            var confirmed = false
            try {
                val call = ToolCall(UUID.randomUUID().toString(), "git", obj("action" to action, "value" to value, "remote" to remote, "branch" to branch, "expected_url" to expected))
                val result = GitTools(Workspace(directory), GitCredentialStore(context)) { progress -> mutable.update { it.copy(output = progress) } }.execute(call)
                mutable.update { it.copy(output = result) }; confirmed = true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutable.update { it.copy(error = "Git 操作未完成，请检查仓库实际状态：${failure.message.orEmpty()}") }
            } finally {
                try { lifecycle.finish(ticket, confirmed) }
                catch (failure: Exception) { mutable.update { it.copy(error = "Git 操作记录未能保存，请核查仓库状态") } }
                finally { mutable.update { it.copy(running = false) } }
            }
        }
    }
    fun cancel() { if (state.value.running) { mutable.update { it.copy(error = "正在请求停止传输，请核查仓库实际状态") }; job?.cancel() } }
}
