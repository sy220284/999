package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.io.File

object OriginalSkillConfig {
    fun roots(text: String): List<String> = text.lineSequence().filter { it.isNotBlank() }.map { value ->
        require(File(value).isAbsolute && '\u0000' !in value) { "额外技能目录需要手机上的绝对路径，每行一个" }; value
    }.distinct().toList()
    fun remoteParams(plugin: OriginalPlugin, name: String): JsonObject {
        val remoteId = plugin.summary.string("remotePluginId")
        require(plugin.marketplacePath == null && plugin.marketplace.isNotBlank() && remoteId.isNotBlank() && name.isNotBlank()) { "此插件没有完整的远程技能身份" }
        return buildJsonObject { put("remoteMarketplaceName", plugin.marketplace); put("remotePluginId", remoteId); put("skillName", name) }
    }
}
