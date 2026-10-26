package cn.codex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files

@Serializable
data class ProjectSkillConfig(val extraRoots: List<String> = emptyList())

/**
 * 项目内部的技能目录注册与检索。其他应用的私有目录不可访问，
 * 额外路径必须位于当前 Workspace 内，避免通过符号链接越界读取。
 */
class SkillCatalog(private val fs: Workspace) {
    private val configPath = ".codex/skill-roots.json"
    fun extraRoots(): List<String> =
        fs.resolve(configPath).takeIf { it.isFile }?.let { codec.decodeFromString<ProjectSkillConfig>(fs.read(configPath)).extraRoots }
            ?: emptyList()

    fun previewConfig(roots: List<String>): FileChange {
        require(roots.size <= 16) { "最多添加 16 个额外技能目录" }
        val normalized = roots.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        normalized.forEach { root ->
            require(root != "." && root != ".." && fs.resolve(root) != fs.root.canonicalFile) { "技能根目录不能是整个项目" }
            // 不允许将配置指向工作区之外；目录可先配置，稍后再创建。
            fs.resolve(root)
        }
        return fs.previewWrite(configPath, codec.encodeToString(ProjectSkillConfig(normalized)))
    }

    fun list(): List<String> {
        val roots = (listOf("技能", ".agents/skills") + extraRoots()).distinct()
        return roots.flatMap { root ->
            val folder = fs.resolve(root)
            if (!folder.isDirectory || Files.isSymbolicLink(folder.toPath())) emptyList()
            else folder.walkTopDown()
                .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) && fs.resolve(dir.relativeTo(fs.root).path) == dir.canonicalFile }
                .maxDepth(5)
                .filter { it.isFile && it.name == "SKILL.md" && !Files.isSymbolicLink(it.toPath()) }
                .map { it.relativeTo(fs.root).path }.toList()
        }.distinct().sorted()
    }
}
