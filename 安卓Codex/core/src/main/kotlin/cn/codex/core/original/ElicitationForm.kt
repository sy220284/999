package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI

/** MCP 平面表单。对未支持的嵌套/组合 schema 返回不可用，保留拒绝入口。服务器仍负责最终 schema 校验。 */
class ElicitationForm(val schema: JsonObject) {
    data class Field(val name: String, val schema: JsonObject, val required: Boolean) {
        val type get() = schema.string("type", "string")
        val title get() = schema.string("title", name)
        val description get() = schema.string("description")
        val options get() = schema["enum"] as? JsonArray
    }
    private val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    val fields = (schema["properties"] as? JsonObject).orEmpty().map { (name, raw) -> Field(name, raw as? JsonObject ?: buildJsonObject { put("type", "unsupported") }, name in required) }
    val supported = schema.string("type") == "object" && schema.keys.none { it in setOf("oneOf", "anyOf", "allOf", "not", "if", "then", "else", "dependentSchemas") } && fields.size <= 50 && fields.all { field ->
        field.type in setOf("string", "number", "integer", "boolean") &&
            field.schema.keys.none { it in setOf("oneOf", "anyOf", "allOf", "not", "if", "then", "else", "const", "items", "properties", "dependentSchemas") } &&
            (field.options == null || field.options!!.all { it is JsonPrimitive }) &&
            (field.schema.string("pattern").isBlank() || runCatching { Regex(field.schema.string("pattern")) }.isSuccess)
    } && required.all { key -> fields.any { it.name == key } }
    fun content(values: Map<String, String>): JsonObject {
        require(supported) { "该表单包含尚未支持的字段结构" }
        return buildJsonObject {
            for (field in fields) {
                val text = values[field.name]
                if (text == null) { require(!field.required) { "请填写${field.title}" }; continue }
                val value = when (field.type) {
                    "boolean" -> JsonPrimitive(when (text) { "true" -> true; "false" -> false; else -> error("${field.title}请选择是或否") })
                    "number", "integer" -> {
                        val parsed = runCatching { Json.parseToJsonElement(text) as? JsonPrimitive }.getOrNull()
                        require(parsed != null && !parsed.isString && parsed != JsonNull && parsed.booleanOrNull == null) { "${field.title}需要有效数字" }
                        val number = runCatching { BigDecimal(parsed.content) }.getOrNull()
                        require(number != null) { "${field.title}需要有效数字" }
                        if (field.type == "integer") require(number.stripTrailingZeros().scale() <= 0) { "${field.title}需要整数" }
                        for ((key, lower, exclusive) in listOf(Triple("minimum", true, false), Triple("maximum", false, false), Triple("exclusiveMinimum", true, true), Triple("exclusiveMaximum", false, true))) {
                            val bound = (field.schema[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull() ?: continue
                            val comparison = number.compareTo(bound)
                            require(if (lower) comparison > 0 || !exclusive && comparison == 0 else comparison < 0 || !exclusive && comparison == 0) { "${field.title}超出允许范围" }
                        }
                        parsed
                    }
                    else -> {
                        val length = text.codePointCount(0, text.length)
                        val min = field.schema["minLength"]?.jsonPrimitive?.intOrNull ?: 0
                        val max = field.schema["maxLength"]?.jsonPrimitive?.intOrNull ?: 1_000_000
                        require(length in min..max) { "${field.title}长度超出范围" }
                        field.schema.string("pattern").takeIf { it.isNotBlank() }?.let { require(Regex(it).containsMatchIn(text)) { "${field.title}格式不匹配" } }
                        when (field.schema.string("format")) {
                            "email" -> require(text.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "${field.title}需要有效邮箱" }
                            "uri" -> require(runCatching { URI(text).isAbsolute }.getOrDefault(false)) { "${field.title}需要完整地址" }
                        }
                        JsonPrimitive(text)
                    }
                }
                field.options?.let { allowed -> require(value in allowed) { "${field.title}请选择列表中的值" } }
                put(field.name, value)
            }
        }
    }
}
