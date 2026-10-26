package cn.codex.android

import cn.codex.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream

/** JGit 在应用进程内操作仓库，不依赖手机安装 git 命令。 */
class GitTools(private val fs: Workspace): ToolExtension {
    override fun specs() = listOf(ToolSpec("git", "本机 Git 操作。action 可用 init/status/diff/add/commit/log/branch/checkout，提交信息使用中文；网络克隆和推送需用户另行配置，当前不支持", buildJsonObject {
        put("type", "object"); put("properties", buildJsonObject { put("action", obj("type" to "string")); put("value", obj("type" to "string")) }); put("required", buildJsonArray { add("action") })
    }))
    override suspend fun execute(call: ToolCall): String = withContext(Dispatchers.IO) {
        val action = call.arguments.string("action"); val value = call.arguments.string("value")
        if (action == "init") { Git.init().setDirectory(fs.root).call().close(); return@withContext "已初始化 Git 仓库" }
        Git.open(fs.root).use { git ->
            when (action) {
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
