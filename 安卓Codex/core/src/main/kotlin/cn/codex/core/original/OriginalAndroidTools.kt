package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** 显式批准后执行的两个手机项目只读工具。工具名不映射任意命令。 */
class OriginalAndroidTools(private val root: File) {
    fun execute(params: JsonObject): JsonObject = try {
        require(params["namespace"] == null || params["namespace"] == JsonNull) { "本机未注册此工具命名空间" }
        val args = params["arguments"] as? JsonObject ?: error("工具参数必须是对象")
        require(args.keys.all { it == "path" }) { "未知工具参数" }
        require(args["path"] == null || (args["path"] as? JsonPrimitive)?.isString == true) { "路径参数必须是文本" }
        val input = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "."
        val path = File(OriginalFileData.path(root, input)).canonicalFile
        val text = when (params.string("tool")) {
            "android_project_list" -> {
                require(path.isDirectory) { "目录不存在" }
                val entries = path.listFiles()?.sortedBy { it.name } ?: error("无法读取目录")
                buildJsonObject {
                    put("path", path.relativeTo(root.canonicalFile).path.ifBlank { "." })
                    put("truncated", entries.size > 200)
                    put("entries", buildJsonArray { entries.take(200).forEach { entry -> add(buildJsonObject { put("name",entry.name);put("directory", java.nio.file.Files.isDirectory(entry.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));put("symbolicLink",java.nio.file.Files.isSymbolicLink(entry.toPath())) }) } })
                }.toString()
            }
            "android_project_read" -> {
                require(path.isFile) { "文件不存在" }
                val bytes = path.inputStream().use { it.readNBytes(65_537) }
                require(bytes.size <= 65_536 && bytes.none { it == 0.toByte() }) { "只支持64 KiB以内的UTF-8文本，请使用文件页查看或导出" }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
            else -> error("本机没有注册此工具")
        }
        response(true,text)
    } catch (failure: Exception) { response(false, failure.message ?: "工具读取失败") }
    companion object {
        fun response(success: Boolean, text: String) = buildJsonObject { put("success",success);put("contentItems",buildJsonArray { add(buildJsonObject { put("type","inputText");put("text",text) }) }) }
        fun definitions() = buildJsonArray {
            listOf("android_project_list" to "列出手机当前项目目录，只读，需用户批准。", "android_project_read" to "读取手机当前项目内64 KiB以内UTF-8文本，只读，需用户批准。").forEach { (name,description) -> add(buildJsonObject {
                put("type","function");put("name",name);put("description",description)
                put("inputSchema",buildJsonObject { put("type","object");put("additionalProperties",false);if(name == "android_project_read")put("required",buildJsonArray { add("path") });put("properties",buildJsonObject { put("path",buildJsonObject { put("type","string");put("description","相对项目路径；列目录默认当前目录") }) }) })
            }) }
        }
    }
}
