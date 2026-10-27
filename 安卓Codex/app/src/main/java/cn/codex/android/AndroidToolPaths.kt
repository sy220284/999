package cn.codex.android

import android.content.Context
import android.system.Os
import java.io.File

/** 别名只链接安装目录中的真实程序，不在可写目录生成可执行脚本或复制 ELF。 */
object AndroidToolPaths {
    fun install(context: Context): String {
        val bin = File(context.filesDir, "原版工具/bin").apply { mkdirs() }
        val target = File(context.applicationInfo.nativeLibraryDir, "libcodex_rg.so")
        val alias = File(bin, "rg")
        if (target.isFile) {
            val temporary = File.createTempFile("rg-", ".link", bin)
            temporary.delete()
            try {
                Os.symlink(target.absolutePath, temporary.absolutePath)
                Os.rename(temporary.absolutePath, alias.absolutePath)
            } finally { temporary.delete() }
        } else alias.delete()
        return bin.absolutePath + ":" + System.getenv("PATH").orEmpty()
    }
}
