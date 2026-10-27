package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI

/** 表单本地校验；服务端仍执行最终校验。外部引用与未知验证词明确拒绝。 */
class JsonSchemaFormValidator(private val root: JsonObject) {
    private val annotations = setOf("\$schema", "\$id", "\$comment", "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly", "enumNames")
    private val keywords = setOf("type", "enum", "const", "properties", "required", "additionalProperties", "patternProperties", "dependentRequired", "dependentSchemas", "minProperties", "maxProperties", "items", "prefixItems", "contains", "minContains", "maxContains", "minItems", "maxItems", "uniqueItems", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "format", "oneOf", "anyOf", "allOf", "not", "if", "then", "else", "\$defs", "definitions", "\$ref")
    private fun reference(ref: String): JsonElement {
        require(ref == "#" || ref.startsWith("#/")) { "表单包含外部引用，暂不能离线解析" }
        return if (ref == "#") root else ref.removePrefix("#/").split('/').fold(root as JsonElement) { node, key ->
            (node as? JsonObject)?.get(key.replace("~1", "/").replace("~0", "~")) ?: error("表单引用不存在")
        }
    }
    fun supported(schema: JsonElement = root, refs: Set<String> = emptySet()): Boolean {
        if (schema is JsonPrimitive) return schema.booleanOrNull != null
        val obj = schema as? JsonObject ?: return false
        if (obj.keys.any { it !in keywords && it !in annotations }) return false
        val ref = obj.string("\$ref")
        if (ref.isNotBlank()) {
            if (ref in refs) return false // 不展示无法有限展开的递归表单。
            if (!runCatching { supported(reference(ref), refs + ref) }.getOrDefault(false)) return false
        }
        for (key in listOf("properties", "patternProperties", "dependentSchemas", "\$defs", "definitions")) {
            if ((obj[key] as? JsonObject)?.values?.any { !supported(it, refs) } == true) return false
        }
        for (key in listOf("oneOf", "anyOf", "allOf", "prefixItems")) if ((obj[key] as? JsonArray)?.any { !supported(it, refs) } == true) return false
        for (key in listOf("items", "contains", "not", "if", "then", "else", "additionalProperties")) if (obj[key]?.let { !supported(it, refs) } == true) return false
        return obj.string("pattern").let { it.isBlank() || runCatching { Regex(it) }.isSuccess }
    }
    private fun equal(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
            val x = a.content.toBigDecimalOrNull(); val y = b.content.toBigDecimalOrNull()
            if (x != null && y != null) return x.compareTo(y) == 0
        }
        if (a is JsonArray && b is JsonArray) return a.size == b.size && a.indices.all { equal(a[it], b[it]) }
        if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.all { (key, value) -> equal(value, b.getValue(key)) }
        return a == b
    }
    private fun matches(value: JsonElement, schema: JsonElement, path: String): Boolean = try { validate(value, schema, path); true } catch (_: IllegalArgumentException) { false }
    fun validate(value: JsonElement, schema: JsonElement = root, path: String = "表单") {
        if (schema is JsonPrimitive) { require(schema.booleanOrNull == true) { "$path 不符合规则" }; return }
        val s = schema.jsonObject
        s.string("\$ref").takeIf(String::isNotBlank)?.let { validate(value, reference(it), path) }
        (s["allOf"] as? JsonArray)?.forEach { validate(value, it, path) }
        (s["anyOf"] as? JsonArray)?.let { require(it.any { part -> matches(value, part, path) }) { "$path 不符合任一可选结构" } }
        (s["oneOf"] as? JsonArray)?.let { require(it.count { part -> matches(value, part, path) } == 1) { "$path 必须且只能符合一种结构" } }
        s["not"]?.let { require(!matches(value, it, path)) { "$path 使用了禁止的结构" } }
        s["if"]?.let { condition -> s[if (matches(value, condition, path)) "then" else "else"]?.let { validate(value, it, path) } }
        (s["enum"] as? JsonArray)?.let { require(it.any { allowed -> equal(value, allowed) }) { "$path 请从允许值中选择" } }
        s["const"]?.let { require(equal(value, it)) { "$path 必须使用指定值" } }
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
        val actualType = when { value == JsonNull -> "null"; value is JsonObject -> "object"; value is JsonArray -> "array"; value is JsonPrimitive && value.isString -> "string"; number != null -> if (number.stripTrailingZeros().scale() <= 0) "integer" else "number"; else -> "boolean" }
        val types = when (val raw = s["type"]) { is JsonArray -> raw.map { it.jsonPrimitive.content }; is JsonPrimitive -> listOf(raw.content); else -> emptyList() }
        require(types.isEmpty() || actualType in types || actualType == "integer" && "number" in types) { "$path 的数据类型不正确" }
        fun bound(key: String) = (s[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
        fun size(size: Int, min: String, max: String) {
            require(bound(min)?.let { size.toBigDecimal() >= it } != false && bound(max)?.let { size.toBigDecimal() <= it } != false) { "$path 数量超出允许范围" }
        }
        if (number != null) {
            require(bound("minimum")?.let { number >= it } != false && bound("maximum")?.let { number <= it } != false && bound("exclusiveMinimum")?.let { number > it } != false && bound("exclusiveMaximum")?.let { number < it } != false) { "$path 数值超出范围" }
            bound("multipleOf")?.let { require(it > BigDecimal.ZERO && number.remainder(it).compareTo(BigDecimal.ZERO) == 0) { "$path 不符合数值步长" } }
        }
        if (value is JsonObject) {
            size(value.size, "minProperties", "maxProperties")
            (s["required"] as? JsonArray).orEmpty().forEach { require(value.containsKey(it.jsonPrimitive.content)) { "$path 请填写 ${it.jsonPrimitive.content}" } }
            val properties = s["properties"] as? JsonObject ?: buildJsonObject {}
            val patterns = (s["patternProperties"] as? JsonObject).orEmpty().mapKeys { Regex(it.key) }
            value.forEach { (key, item) ->
                properties[key]?.let { validate(item, it, "$path.$key") }
                val patternMatches = patterns.filterKeys { it.containsMatchIn(key) }
                patternMatches.values.forEach { validate(item, it, "$path.$key") }
                if (key !in properties && patternMatches.isEmpty()) s["additionalProperties"]?.let { validate(item, it, "$path.$key") }
            }
            (s["dependentRequired"] as? JsonObject).orEmpty().forEach { (key, required) -> if (key in value) required.jsonArray.forEach { require(it.jsonPrimitive.content in value) { "$path.$key 还需要 ${it.jsonPrimitive.content}" } } }
            (s["dependentSchemas"] as? JsonObject).orEmpty().forEach { (key, part) -> if (key in value) validate(value, part, path) }
        }
        if (value is JsonArray) {
            size(value.size, "minItems", "maxItems")
            if (s["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true) require(value.indices.none { i -> (i + 1 until value.size).any { equal(value[i], value[it]) } }) { "$path 不能包含重复项" }
            val prefix = (s["prefixItems"] as? JsonArray).orEmpty()
            value.forEachIndexed { index, item -> (prefix.getOrNull(index) ?: s["items"])?.let { validate(item, it, "$path[${index + 1}]") } }
            s["contains"]?.let { part -> val count = value.count { matches(it, part, path) }; require(count >= (bound("minContains")?.toInt() ?: 1) && count <= (bound("maxContains")?.toInt() ?: Int.MAX_VALUE)) { "$path 包含项不符合要求" } }
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content; size(text.codePointCount(0, text.length), "minLength", "maxLength")
            s.string("pattern").takeIf(String::isNotBlank)?.let { require(Regex(it).containsMatchIn(text)) { "$path 格式不匹配" } }
            when (s.string("format")) {
                "email" -> require(text.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "$path 需要有效邮箱" }
                "uri" -> require(runCatching { URI(text).isAbsolute }.getOrDefault(false)) { "$path 需要完整地址" }
                "date" -> require(runCatching { java.time.LocalDate.parse(text) }.isSuccess) { "$path 需要有效日期" }
                "date-time" -> require(runCatching { java.time.OffsetDateTime.parse(text) }.isSuccess) { "$path 需要带时区的日期时间" }
                "uuid" -> require(runCatching { java.util.UUID.fromString(text) }.isSuccess) { "$path 需要有效 UUID" }
            }
        }
    }
}
