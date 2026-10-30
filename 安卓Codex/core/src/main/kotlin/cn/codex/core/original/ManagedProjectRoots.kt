package cn.codex.core.original
import cn.codex.core.string
import java.io.File
import kotlinx.serialization.json.JsonObject
/** 只认可实际原版托管记录；未知目录不能悄悄映射成默认项目。 */
object ManagedProjectRoots {
    fun resolve(normal:File,cwd:File,managed:List<JsonObject>):File{
        val actual=cwd.canonicalFile;require(actual.isDirectory){"会话项目目录不存在"}
        val roots=listOf(normal.canonicalFile)+managed.map{val raw=it.string("root");require(raw.isNotBlank());File(raw).canonicalFile}
        require(roots.any{root->root.parentFile!=null && (actual==root || actual.toPath().startsWith(root.toPath()))}){"会话目录不是当前项目或原版托管工作树，请先核查配置和目录"}
        return actual
    }
}
