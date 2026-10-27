package cn.codex.android

import cn.codex.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.eclipse.jgit.lib.ProgressMonitor
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream

/** JGit 在应用进程内操作仓库，不依赖手机安装 git 命令。 */
class GitTools(private val fs: Workspace, private val credentials: GitCredentialStore? = null, private val progress: (String) -> Unit = {}): ToolExtension {
    override fun specs() = listOf(ToolSpec("git", "手机 Git 操作。action 可用 init/status/diff/add/commit/log/branch/checkout/remotes/set_remote/clone/fetch/pull/push。网络仅支持 HTTPS；fetch/pull/push 必须提供确认过的 expected_url；push 仅推送 branch 指定的单个分支，不强制推送。value 用作提交说明、分支、克隆/远端地址。凭证在 Git 页面加密配置，禁止把令牌放入参数或地址。", buildJsonObject {
        put("type", "object"); put("properties", buildJsonObject { put("action", obj("type" to "string")); put("value", obj("type" to "string")); put("remote", obj("type" to "string")); put("branch", obj("type" to "string")); put("expected_url", obj("type" to "string")) }); put("required", buildJsonArray { add("action") })
    }))
    override suspend fun execute(call: ToolCall): String = withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        val monitor = object : ProgressMonitor {
            private var completed = 0
            override fun start(totalTasks: Int) = Unit
            override fun beginTask(title: String, totalWork: Int) { completed = 0; progress("Git 正在处理：$title") }
            override fun update(count: Int) { completed += count; progress("Git 已处理 $completed 项") }
            override fun endTask() = Unit
            override fun isCancelled() = !operationContext.isActive
            override fun showDuration(enabled: Boolean) = Unit
        }
        val action = call.arguments.string("action"); val value = call.arguments.string("value")
        if (action == "init") { Git.init().setDirectory(fs.root).call().close(); return@withContext "已初始化 Git 仓库" }
        val remote = call.arguments.string("remote", "origin")
        val branch = call.arguments.string("branch")
        require(Repository.isValidRefName("refs/remotes/$remote/HEAD")) { "远端名称不正确" }
        if (action == "clone") {
            GitCredentialStore.address(value)
            require(fs.root.listFiles().isNullOrEmpty()) { "克隆需要空项目目录，请先新建一个项目" }
            val clone = Git.cloneRepository().setURI(value).setDirectory(fs.root).setRemote(remote).setTimeout(120).setProgressMonitor(monitor)
            if (branch.isNotBlank()) clone.setBranch(branch)
            credentials?.provider(value)?.let { clone.setCredentialsProvider(it) }
            clone.call().use { }
            return@withContext "已克隆仓库到当前项目"
        }
        Git.open(fs.root).use { git ->
            when (action) {
                "remotes" -> git.remoteList().call().joinToString("\n") { item -> "${item.name}：${item.getURIs().joinToString()}" + if (item.getPushURIs().isNotEmpty()) "\n推送：${item.getPushURIs().joinToString()}" else "" }
                "set_remote" -> {
                    GitCredentialStore.address(value)
                    git.repository.config.apply { setString("remote", remote, "url", value); setString("remote", remote, "pushurl", value); setString("remote", remote, "fetch", "+refs/heads/*:refs/remotes/$remote/*"); save() }
                    "已保存远端 $remote"
                }
                "fetch", "pull", "push" -> {
                    val config = RemoteConfig(git.repository.config, remote)
                    val targets = if (action == "push" && config.getPushURIs().isNotEmpty()) config.getPushURIs() else config.getURIs()
                    require(targets.size == 1) { "请选择只有一个明确地址的远端，避免操作多个目标" }
                    val url = targets.single().toPrivateString()
                    GitCredentialStore.address(url)
                    require(call.arguments.string("expected_url") == url) { "远端地址与确认值不同，请先查看 remotes 后重新确认" }
                    val provider = credentials?.provider(url)
                    when (action) {
                        "fetch" -> { val command = git.fetch().setRemote(remote).setTimeout(120).setProgressMonitor(monitor); if (provider != null) command.setCredentialsProvider(provider); command.call(); "已获取远端，工作目录未合并" }
                        "pull" -> {
                            require(Repository.isValidRefName("refs/heads/$branch")) { "请填写需要拉取的分支" }
                            val command = git.pull().setRemote(remote).setRemoteBranchName(branch).setRebase(false).setTimeout(120).setProgressMonitor(monitor)
                            if (provider != null) command.setCredentialsProvider(provider)
                            val result = command.call()
                            if (result.isSuccessful) "已拉取并合并远端分支 $branch" else "拉取尚未完成，请检查冲突与工作目录：${result.mergeResult?.mergeStatus}"
                        }
                        else -> {
                            require(Repository.isValidRefName("refs/heads/$branch") && git.repository.findRef("refs/heads/$branch") != null) { "请填写存在的本地分支" }
                            val command = git.push().setRemote(remote).setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch")).setTimeout(120).setProgressMonitor(monitor)
                            if (provider != null) command.setCredentialsProvider(provider)
                            val updates = command.call().flatMap { it.remoteUpdates }
                            require(updates.isNotEmpty() && updates.all { it.status in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE) }) { "推送未全部成功：${updates.joinToString { it.status.toString() }}" }
                            "已推送分支 $branch"
                        }
                    }
                }
                "status" -> git.status().call().let { "新增：${it.untracked}\n已修改：${it.modified}\n暂存：${it.added + it.changed}\n已删除：${it.missing + it.removed}\n冲突：${it.conflicting}" }
                "diff" -> ByteArrayOutputStream().use { out -> DiffFormatter(out).use { formatter -> formatter.setRepository(git.repository); git.diff().call().forEach { formatter.format(it) } }; out.toString("UTF-8").ifBlank { "没有未暂存修改" } }
                "add" -> { fs.resolve(value.ifBlank { "." }); git.add().addFilepattern(value.ifBlank { "." }).call(); "已加入暂存区" }
                "commit" -> { require(value.isNotBlank()) { "提交说明不能为空" }; git.commit().setMessage(value).setAuthor("Codex 手机用户", "codex@localhost").setCommitter("Codex 手机用户", "codex@localhost").call().name }
                "log" -> git.log().setMaxCount(30).call().joinToString("\n") { "${it.name.take(8)} ${it.shortMessage}" }
                "branch" -> git.branchList().call().joinToString("\n") { it.name.removePrefix("refs/heads/") }
                "checkout" -> { require(value.isNotBlank()); git.checkout().setName(value).call(); "已切换分支：$value" }
                else -> error("不支持的 Git 操作：$action")
            }
        }
    }
}
