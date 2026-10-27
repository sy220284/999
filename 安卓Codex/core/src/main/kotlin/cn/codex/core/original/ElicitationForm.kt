package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 普通字段使用原生控件；嵌套对象/数组使用 JSON 内容并执行同一份结构校验。 */
class ElicitationForm(val schema: JsonObject) {
    private val validator = JsonSchemaFormValidator(schema)
    data class Field(val name: String, val schema: JsonObject, val required: Boolean) {
        val type get() = (schema["type"] as? JsonPrimitive)?.contentOrNull ?: if (options?.firstOrNull() is JsonPrimitive && options!!.first().jsonPrimitive.isString) "string" else "json"
        val title get() = schema.string("title", name)
        val description get() = schema.string("description")
        val options: JsonArray? get() = (schema["enum"] as? JsonArray)?.takeIf { it.all { option -> option is JsonPrimitive } } ?: (schema["oneOf"] as? JsonArray)?.takeIf { it.all { choice -> (choice as? JsonObject)?.get("const") is JsonPrimitive } }?.let { JsonArray(it.map { choice -> choice.jsonObject.getValue("const") }) }
        fun label(index: Int) = (schema["enumNames"] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.contentOrNull
            ?: (schema["oneOf"] as? JsonArray)?.getOrNull(index)?.jsonObject?.string("title")?.takeIf(String::isNotBlank)
            ?: options?.getOrNull(index)?.jsonPrimitive?.content.orEmpty()
    }
    private val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    val fields = (schema["properties"] as? JsonObject).orEmpty().map { (name, raw) -> Field(name, raw as? JsonObject ?: buildJsonObject {}, name in required) }
    val rawInput = schema.keys.any { it in setOf("oneOf", "anyOf", "allOf", "\$ref", "if") }
    val supported = (schema["type"] as? JsonPrimitive)?.content == "object" && validator.supported()
    val initialValues = if (rawInput) mapOf("__form_json" to (schema["default"] ?: buildJsonObject {}).toString()) else fields.mapNotNull { field -> field.schema["default"]?.let { field.name to if (it is JsonPrimitive && it.isString) it.content else it.toString() } }.toMap()
    fun content(values: Map<String, String>): JsonObject {
        require(supported) { "表单包含无法本地解析的引用或验证规则" }
        val result = if (rawInput) Json.parseToJsonElement(values["__form_json"].orEmpty()).jsonObject else buildJsonObject {
            fields.forEach { field -> values[field.name]?.let { text ->
                val value = if (field.type == "string") JsonPrimitive(text) else runCatching { Json.parseToJsonElement(text) }.getOrElse { throw IllegalArgumentException("${field.title}需要有效的 JSON 内容") }
                put(field.name, value)
            } }
        }
        validator.validate(result)
        return result
    }
}
