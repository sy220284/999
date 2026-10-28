package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*

/** 普通字段使用原生控件；嵌套对象/数组使用 JSON 内容并执行同一份结构校验。 */
class ElicitationForm(val schema: JsonObject) {
    private val normalized=runCatching{normalize(schema,0)}.getOrNull() as? JsonObject
    private val validator=normalized?.let(::JsonSchemaFormValidator)
    data class Field(val name: String, val schema: JsonObject, val required: Boolean) {
        val type get() = if(schema.string("type")=="openai/imagePicker")"string"else (schema["type"] as? JsonPrimitive)?.contentOrNull ?: if (options?.firstOrNull() is JsonPrimitive && options!!.first().jsonPrimitive.isString) "string" else "json"
        val title get() = schema.string("title", name)
        val description get() = schema.string("description")
        val imageOptions:List<JsonObject> get()=if(schema.string("type")=="openai/imagePicker")(schema["items"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}.take(40)else emptyList()
        val multiOptions:JsonArray? get(){val items=schema["items"] as? JsonObject ?: return null;return (items["enum"] as? JsonArray) ?: ((items["anyOf"] ?: items["oneOf"]) as? JsonArray)?.takeIf{it.all{part->(part as? JsonObject)?.get("const") is JsonPrimitive}}?.let{JsonArray(it.map{part->part.jsonObject.getValue("const")})}}
        fun multiLabel(index:Int):String{val items=schema["items"] as? JsonObject;return ((items?.get("anyOf") ?: items?.get("oneOf")) as? JsonArray)?.getOrNull(index)?.jsonObject?.string("title")?.takeIf{it.isNotBlank()} ?: multiOptions?.getOrNull(index)?.jsonPrimitive?.content.orEmpty()}
        val options: JsonArray? get() = if(imageOptions.isNotEmpty())JsonArray(imageOptions.map{it.getValue("id")})else (schema["enum"] as? JsonArray)?.takeIf { it.all { option -> option is JsonPrimitive } } ?: (schema["oneOf"] as? JsonArray)?.takeIf { it.all { choice -> (choice as? JsonObject)?.get("const") is JsonPrimitive } }?.let { JsonArray(it.map { choice -> choice.jsonObject.getValue("const") }) }
        fun label(index: Int) = imageOptions.getOrNull(index)?.string("title")?.takeIf{it.isNotBlank()} ?: (schema["enumNames"] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.contentOrNull
            ?: (schema["oneOf"] as? JsonArray)?.getOrNull(index)?.jsonObject?.string("title")?.takeIf(String::isNotBlank)
            ?: options?.getOrNull(index)?.jsonPrimitive?.content.orEmpty()
    }
    private val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf{part->part.isString}?.content }.toSet()
    val fields = (schema["properties"] as? JsonObject).orEmpty().entries.take(128).map { (name, raw) -> Field(name, raw as? JsonObject ?: buildJsonObject {}, name in required) }
    val rawInput = schema.keys.any { it in setOf("oneOf", "anyOf", "allOf", "\$ref", "if") }
    val supported = schema.toString().toByteArray().size<=256*1024 && (schema["properties"] as? JsonObject).orEmpty().size<=128 && (schema["type"] as? JsonPrimitive)?.content == "object" && validator?.supported()==true
    val initialValues = if (rawInput) mapOf("__form_json" to (schema["default"] ?: buildJsonObject {}).toString()) else fields.mapNotNull { field -> field.schema["default"]?.let { field.name to if (it is JsonPrimitive && it.isString) it.content else it.toString() } }.toMap()
    fun content(values: Map<String, String>): JsonObject {
        require(supported) { "表单包含无法本地解析的引用或验证规则" }
        val result = if (rawInput) Json.parseToJsonElement(values["__form_json"].orEmpty()).jsonObject else buildJsonObject {
            fields.forEach { field -> values[field.name]?.let { text ->
                val value = if (field.type == "string") JsonPrimitive(text) else runCatching { Json.parseToJsonElement(text) }.getOrElse { throw IllegalArgumentException("${field.title}需要有效的 JSON 内容") }
                put(field.name, value)
            } }
        }
        requireNotNull(validator).validate(result)
        return result
    }
    private fun normalize(value:JsonElement,depth:Int):JsonElement {
        require(depth<=64)
        if(value is JsonArray)return JsonArray(value.map{normalize(it,depth+1)})
        if(value !is JsonObject)return value
        if(value.string("type")=="openai/imagePicker"){
            require(value.keys.all{it in setOf("type","title","description","items","default")})
            val items=value.getValue("items").jsonArray;require(items.size in 1..40)
            val ids=items.map{part->val item=part.jsonObject;val id=item.string("id");require(id.isNotBlank() && id.length<=256);JsonPrimitive(id)}
            require(ids.distinct().size==ids.size)
            return buildJsonObject{put("type","string");put("enum",JsonArray(ids));for(key in listOf("title","description","default"))value[key]?.let{put(key,it)}}
        }
        val maps=setOf("properties","patternProperties","dependentSchemas","\$defs","definitions")
        val lists=setOf("oneOf","anyOf","allOf","prefixItems")
        val singles=setOf("items","contains","not","if","then","else","additionalProperties","propertyNames","unevaluatedProperties","unevaluatedItems","additionalItems")
        return JsonObject(value.mapValues{(key,part)->when {
            key in maps && part is JsonObject->JsonObject(part.mapValues{(_,child)->normalize(child,depth+1)})
            key in lists && part is JsonArray->JsonArray(part.map{normalize(it,depth+1)})
            key in singles->normalize(part,depth+1)
            else->part // const/default/enum/examples 是实例数据，不是子schema。
        }})
    }
}
