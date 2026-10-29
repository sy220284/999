package cn.codex.android

import android.content.Context
import android.system.Os
import java.io.File

/** 别名只链接安装目录中的真实程序，不在可写目录生成可执行脚本或复制 ELF。 */
object AndroidToolPaths {
    val programs: Map<String, String> = linkedMapOf("codex" to "libcodex_cli.so", "rg" to "libcodex_rg.so", "git" to "libcodex_git.so",
        "git-upload-pack" to "libcodex_git.so", "git-receive-pack" to "libcodex_git.so", "git-upload-archive" to "libcodex_git.so",
        "node" to "libcodex_node.so", "python" to "libcodex_python.so", "python3" to "libcodex_python.so",
        "npm" to "libcodex_npm.so", "npx" to "libcodex_npm.so", "git-remote-http" to "libcodex_git_remote_http.so",
        "git-remote-https" to "libcodex_git_remote_http.so", "ssh" to "libcodex_ssh.so", "ssh-keygen" to "libcodex_ssh_keygen.so")
    fun directory(context: Context) = File(context.filesDir, "原版工具/bin")
    fun environment(context: Context): Map<String, String> = mapOf(
        "GIT_EXEC_PATH" to directory(context).absolutePath,
        "GIT_TEMPLATE_DIR" to File(context.filesDir, "原版工具/git-templates").apply { mkdirs() }.absolutePath,
        "GIT_PAGER" to "cat") + AndroidLanguageRuntime.environment() + AndroidSshTrust.environment(context)
    fun install(context: Context): String {
        val bin = directory(context).apply { mkdirs() }
        for ((name, library) in programs) {
            val target = File(context.applicationInfo.nativeLibraryDir, library)
            val alias = File(bin, name)
            if (target.isFile && target.canExecute()) {
                val temporary = File.createTempFile("tool-", ".link", bin)
                temporary.delete()
                try { Os.symlink(target.absolutePath, temporary.absolutePath); Os.rename(temporary.absolutePath, alias.absolutePath) }
                finally { temporary.delete() }
            } else alias.delete()
        }
        return bin.absolutePath + ":" + System.getenv("PATH").orEmpty()
    }
    fun installed(context: Context, library: String): Boolean = File(context.applicationInfo.nativeLibraryDir, library).let { it.isFile && it.canExecute() }
}
