package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 精确保留市场与插件身份，避免仅凭显示名称把请求发向另一个市场。 */
data class OriginalPlugin(val marketplace: String, val marketplacePath: String?, val summary: JsonObject) {
    val id get() = summary.string("id")
    val name get() = summary.string("name")
    val key get() = "$marketplace\u0000${marketplacePath.orEmpty()}\u0000$id"
    val displayName get() = (summary["interface"] as? JsonObject)?.string("displayName").orEmpty().ifBlank { name }
    val description get() = (summary["interface"] as? JsonObject)?.string("shortDescription").orEmpty()
    val installed get() = summary["installed"]?.jsonPrimitive?.booleanOrNull == true
    val enabled get() = summary["enabled"]?.jsonPrimitive?.booleanOrNull == true
    val source get() = summary["source"] as? JsonObject ?: buildJsonObject {}
    val installBlock: String? get() = when {
        id.isBlank() || name.isBlank() || marketplace.isBlank() -> "插件身份不完整"
        installed -> "已经安装"
        summary.string("availability") == "DISABLED_BY_ADMIN" -> "管理员已禁用此插件"
        summary.string("availability") != "AVAILABLE" -> "当前可用状态尚不能识别"
        summary.string("installPolicy") == "NOT_AVAILABLE" -> "当前安装策略不允许安装"
        summary.string("installPolicy") !in setOf("AVAILABLE", "INSTALLED_BY_DEFAULT") -> "当前安装策略尚不能识别"
        else -> null
    }
    fun locator() = buildJsonObject {
        put("pluginName", name)
        if (marketplacePath == null) put("remoteMarketplaceName", marketplace) else put("marketplacePath", marketplacePath)
    }
}
data class OriginalCatalog(val plugins: List<OriginalPlugin>, val errors: List<String>)
object OriginalExtensions {
    fun catalog(value: JsonObject): OriginalCatalog {
        val plugins = (value["marketplaces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { market ->
            (market["plugins"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                OriginalPlugin(market.string("name"), market.string("path").ifBlank { null }, it)
            }
        }
        val errors = (value["marketplaceLoadErrors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { it.string("marketplacePath") + "：" + it.string("message") }
        return OriginalCatalog(plugins, errors)
    }
    fun combine(catalog: OriginalCatalog, installed: OriginalCatalog): OriginalCatalog {
        val values = linkedMapOf<String, OriginalPlugin>()
        (catalog.plugins + installed.plugins).forEach { plugin ->
            val previous = values[plugin.key]
            // 已安装查询具有最新本机状态；缺字段时保留目录里的界面元数据。
            values[plugin.key] = if (previous == null) plugin else plugin.copy(summary = JsonObject(previous.summary + plugin.summary.filterValues { it != JsonNull }))
        }
        return OriginalCatalog(values.values.toList(), (catalog.errors + installed.errors).distinct())
    }
    fun hookRun(value: JsonObject): OriginalItem {
        val output = (value["entries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n") { it.string("text") }
        val text = listOf(value.string("eventName"), value.string("sourcePath"), value.string("statusMessage"), output).filter { it.isNotBlank() }.joinToString("\n")
        val metadata = JsonObject(value.filterKeys { it in setOf("id", "eventName", "threadId", "status", "sourcePath", "startedAt", "completedAt", "durationMs") })
        return OriginalItem(value.string("id"), "hookRun", text.takeLast(256 * 1024), value.string("status"), metadata)
    }
}
