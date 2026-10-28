package cn.codex.core.original

import cn.codex.core.string
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URI
import java.util.IdentityHashMap
import com.google.re2j.Pattern

/** 有界离线求值：不请求外部引用；不支持的词汇明确拒绝，而非跳过规则。 */
class JsonSchemaFormValidator(private val root:JsonObject) {
    private val annotations=setOf("\$schema","\$id","\$anchor","\$comment","title","description","default","examples","deprecated","readOnly","writeOnly","enumNames","contentEncoding","contentMediaType")
    private val keywords=setOf("type","enum","const","properties","required","additionalProperties","patternProperties","propertyNames","dependentRequired","dependentSchemas","minProperties","maxProperties","items","prefixItems","additionalItems","contains","minContains","maxContains","minItems","maxItems","uniqueItems","minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf","minLength","maxLength","pattern","format","oneOf","anyOf","allOf","not","if","then","else","\$defs","definitions","\$ref","unevaluatedProperties","unevaluatedItems")
    private val formats=setOf("email","uri","uri-reference","date","date-time","time","duration","uuid","hostname","ipv4","ipv6","regex","json-pointer")
    private fun reference(ref:String):JsonElement {
        require(ref.startsWith('#')){"表单包含外部引用，未获得的资源不能当作已验证"}
        val fragment=URI(ref).fragment.orEmpty()
        if(fragment.isEmpty())return root
        if(fragment.startsWith('/'))return fragment.drop(1).split('/').fold(root as JsonElement){node,key->
            val token=key.replace("~1","/").replace("~0","~")
            when(node){is JsonObject->node[token];is JsonArray->token.toIntOrNull()?.let(node::getOrNull);else->null} ?: throw IllegalArgumentException("表单引用不存在")
        }
        val matches=mutableListOf<JsonObject>()
        fun scan(node:JsonElement,depth:Int){require(depth<=64);when(node){is JsonObject->{if(node.string("\$anchor")==fragment)matches+=node;node.values.forEach{scan(it,depth+1)}};is JsonArray->node.forEach{scan(it,depth+1)};else->Unit}}
        scan(root,0);require(matches.size==1){"表单锚点缺失或重复"};return matches.single()
    }
    private fun number(value:JsonElement):BigDecimal? {
        val primitive=value as? JsonPrimitive ?: return null
        if(primitive.isString)return null
        val result=primitive.content.toBigDecimalOrNull() ?: return null
        require(result.precision()<=256 && kotlin.math.abs(result.scale().toLong())<=1024){"数值精度超出手机表单预算"}
        return result
    }
    private class SupportBudget(var steps:Int=0)
    fun supported(schema:JsonElement=root):Boolean = supports(schema,emptySet(),0,SupportBudget())
    private fun supports(schema:JsonElement,refs:Set<String>,depth:Int,budget:SupportBudget):Boolean {
        if(depth>64 || ++budget.steps>50000)return false
        if(schema is JsonPrimitive)return schema.booleanOrNull!=null
        val obj=schema as? JsonObject ?: return false
        if(obj.keys.any{it !in keywords && it !in annotations})return false
        // 未实现资源重新定位；含$id时拒绝，避免把跨资源引用误当根文档。
        if("\$id" in obj)return false
        obj["\$ref"]?.let{raw->
            if(raw !is JsonPrimitive || !raw.isString)return false
            val ref=raw.content
            if(obj===root && ref=="#")return false
            if(ref !in refs && !runCatching{supports(reference(ref),refs+ref,depth+1,budget)}.getOrDefault(false))return false
        }
        for(key in listOf("properties","patternProperties","dependentSchemas","\$defs","definitions"))obj[key]?.let{raw->if(raw !is JsonObject || raw.values.any{!supports(it,refs,depth+1,budget)})return false}
        for(key in listOf("oneOf","anyOf","allOf","prefixItems"))obj[key]?.let{raw->if(raw !is JsonArray || (key!="prefixItems" && raw.isEmpty()) || raw.any{!supports(it,refs,depth+1,budget)})return false}
        for(key in listOf("contains","not","if","then","else","additionalProperties","propertyNames","unevaluatedProperties","unevaluatedItems","additionalItems"))if(obj[key]?.let{!supports(it,refs,depth+1,budget)}==true)return false
        obj["items"]?.let{raw->if(raw is JsonArray){if(raw.any{!supports(it,refs,depth+1,budget)})return false}else if(!supports(raw,refs,depth+1,budget))return false}
        obj["format"]?.let{if(it !is JsonPrimitive || !it.isString || it.content !in formats)return false}
        obj["pattern"]?.let{if(it !is JsonPrimitive || !it.isString || it.content.length>2048 || runCatching{Pattern.compile(it.content)}.isFailure)return false}
        (obj["patternProperties"] as? JsonObject)?.keys?.forEach{if(it.length>2048 || runCatching{Pattern.compile(it)}.isFailure)return false}
        obj["type"]?.let{raw->
            val types=when(raw){is JsonPrimitive->if(raw.isString)listOf(raw.content)else return false;is JsonArray->if(raw.all{it is JsonPrimitive && it.isString})raw.map{it.jsonPrimitive.content}else return false;else->return false}
            if(types.isEmpty() || types.distinct().size!=types.size || types.any{it !in setOf("null","boolean","object","array","number","integer","string")})return false
        }
        for(key in listOf("minProperties","maxProperties","minItems","maxItems","minContains","maxContains","minLength","maxLength"))obj[key]?.let{raw->val n=runCatching{number(raw)}.getOrNull();if(n==null || n<BigDecimal.ZERO || n.stripTrailingZeros().scale()>0)return false}
        for(key in listOf("minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf"))obj[key]?.let{raw->val n=runCatching{number(raw)}.getOrNull();if(n==null || key=="multipleOf" && n<=BigDecimal.ZERO)return false}
        obj["uniqueItems"]?.let{if((it as? JsonPrimitive)?.booleanOrNull==null)return false}
        obj["enum"]?.let{if(it !is JsonArray || it.isEmpty())return false}
        obj["required"]?.let{if(it !is JsonArray || it.any{part->part !is JsonPrimitive || !part.isString} || it.distinct().size!=it.size)return false}
        obj["dependentRequired"]?.let{if(it !is JsonObject || it.values.any{raw->raw !is JsonArray || raw.any{part->part !is JsonPrimitive || !part.isString}})return false}
        return true
    }
    private fun equal(a:JsonElement,b:JsonElement,budget:Budget,depth:Int=0):Boolean {
        if(++budget.steps>50000 || depth>64)throw BudgetFailure()
        if(a is JsonPrimitive && b is JsonPrimitive){val x=number(a);val y=number(b);if(x!=null && y!=null)return x.compareTo(y)==0}
        if(a is JsonArray && b is JsonArray)return a.size==b.size && a.indices.all{equal(a[it],b[it],budget,depth+1)}
        if(a is JsonObject && b is JsonObject)return a.keys==b.keys && a.all{(key,value)->equal(value,b.getValue(key),budget,depth+1)}
        return a==b
    }
    private data class Marks(val properties:MutableSet<String> = mutableSetOf(),val items:MutableSet<Int> = mutableSetOf()) {
        fun merge(other:Marks){properties+=other.properties;items+=other.items}
    }
    private class BudgetFailure:IllegalArgumentException("表单求值超出预算或存在不前进的循环引用")
    private class Budget(var steps:Int=0,var depth:Int=0,val active:IdentityHashMap<JsonElement,MutableSet<String>> = IdentityHashMap())
    fun validate(value:JsonElement,schema:JsonElement=root,path:String="表单") {
        require(root.toString().toByteArray().size<=256*1024 && value.toString().toByteArray().size<=512*1024){"表单内容超出预算"}
        require(supported(schema)){"表单规则尚未支持，不能绕过验证"}
        visit(value,schema,path,Budget())
    }
    private fun match(value:JsonElement,schema:JsonElement,path:String,budget:Budget):Marks?=try{visit(value,schema,path,budget)}catch(error:BudgetFailure){throw error}catch(_:IllegalArgumentException){null}
    private fun visit(value:JsonElement,schema:JsonElement,path:String,budget:Budget):Marks {
        if(++budget.steps>50000 || ++budget.depth>64)throw BudgetFailure()
        val paths=budget.active.getOrPut(schema){mutableSetOf()}
        if(!paths.add(path))throw BudgetFailure()
        try{return evaluate(value,schema,path,budget)}finally{paths-=path;budget.depth--}
    }
    private fun evaluate(value:JsonElement,schema:JsonElement,path:String,budget:Budget):Marks {
        val marks=Marks()
        if(schema is JsonPrimitive){require(schema.booleanOrNull==true){"$path 不符合规则"};return marks}
        val s=schema.jsonObject
        s.string("\$ref").takeIf(String::isNotBlank)?.let{marks.merge(visit(value,reference(it),path,budget))}
        (s["allOf"] as? JsonArray)?.forEach{marks.merge(visit(value,it,path,budget))}
        for(key in listOf("anyOf","oneOf"))(s[key] as? JsonArray)?.let{choices->
            val successes=choices.mapNotNull{match(value,it,path,budget)}
            require(if(key=="oneOf")successes.size==1 else successes.isNotEmpty()){"$path 不符合可选结构"};successes.forEach(marks::merge)
        }
        s["not"]?.let{require(match(value,it,path,budget)==null){"$path 使用了禁止的结构"}}
        s["if"]?.let{condition->val result=match(value,condition,path,budget);if(result!=null)marks.merge(result);s[if(result!=null)"then"else"else"]?.let{marks.merge(visit(value,it,path,budget))}}
        (s["enum"] as? JsonArray)?.let{require(it.any{allowed->equal(value,allowed,budget)}){"$path 请从允许值中选择"}}
        s["const"]?.let{require(equal(value,it,budget)){"$path 必须使用指定值"}}
        val n=number(value)
        val actual=when{value==JsonNull->"null";value is JsonObject->"object";value is JsonArray->"array";value is JsonPrimitive && value.isString->"string";n!=null->if(n.stripTrailingZeros().scale()<=0)"integer"else"number";else->"boolean"}
        val types=when(val raw=s["type"]){is JsonArray->raw.map{it.jsonPrimitive.content};is JsonPrimitive->listOf(raw.content);else->emptyList()}
        require(types.isEmpty() || actual in types || actual=="integer" && "number"in types){"$path 的数据类型不正确"}
        fun bound(key:String)=s[key]?.let(::number)
        fun size(count:Int,min:String,max:String){require(bound(min)?.let{count.toBigDecimal()>=it}!=false && bound(max)?.let{count.toBigDecimal()<=it}!=false){"$path 数量超出允许范围"}}
        if(n!=null){
            require(bound("minimum")?.let{n>=it}!=false && bound("maximum")?.let{n<=it}!=false && bound("exclusiveMinimum")?.let{n>it}!=false && bound("exclusiveMaximum")?.let{n<it}!=false){"$path 数值超出范围"}
            bound("multipleOf")?.let{require(n.remainder(it).compareTo(BigDecimal.ZERO)==0){"$path 不符合数值步长"}}
        }
        if(value is JsonObject){
            size(value.size,"minProperties","maxProperties")
            (s["required"] as? JsonArray).orEmpty().forEach{require(it.jsonPrimitive.content in value){"$path 请填写 ${it.jsonPrimitive.content}"}}
            val properties=(s["properties"] as? JsonObject).orEmpty()
            val patterns=(s["patternProperties"] as? JsonObject).orEmpty().mapKeys{Pattern.compile(it.key)}
            value.forEach{(key,item)->
                properties[key]?.let{visit(item,it,"$path/"+key.replace("~","~0").replace("/","~1"),budget);marks.properties+=key}
                val matched=patterns.filterKeys{it.matcher(key).find()}
                matched.values.forEach{visit(item,it,"$path/"+key.replace("~","~0").replace("/","~1"),budget);marks.properties+=key}
                if(key !in properties && matched.isEmpty())s["additionalProperties"]?.let{visit(item,it,"$path/"+key.replace("~","~0").replace("/","~1"),budget);marks.properties+=key}
                s["propertyNames"]?.let{visit(JsonPrimitive(key),it,"$path 的字段名",budget)}
            }
            (s["dependentRequired"] as? JsonObject).orEmpty().forEach{(key,required)->if(key in value)required.jsonArray.forEach{require(it.jsonPrimitive.content in value){"$path.$key 还需要 ${it.jsonPrimitive.content}"}}}
            (s["dependentSchemas"] as? JsonObject).orEmpty().forEach{(key,part)->if(key in value)marks.merge(visit(value,part,path,budget))}
            s["unevaluatedProperties"]?.let{rule->value.forEach{(key,item)->if(key !in marks.properties){visit(item,rule,"$path/"+key.replace("~","~0").replace("/","~1"),budget);marks.properties+=key}}}
        }
        if(value is JsonArray){
            size(value.size,"minItems","maxItems")
            if(s["uniqueItems"]?.jsonPrimitive?.booleanOrNull==true)require(value.indices.none{i->(i+1 until value.size).any{equal(value[i],value[it],budget)}}){"$path 不能包含重复项"}
            val legacy=s["items"] as? JsonArray;val prefix=(s["prefixItems"] as? JsonArray) ?: legacy ?: JsonArray(emptyList())
            value.forEachIndexed{i,item->val rule=prefix.getOrNull(i) ?: if(legacy!=null)s["additionalItems"]else s["items"];rule?.let{visit(item,it,"$path/$i",budget);marks.items+=i}}
            s["contains"]?.let{rule->val matched=value.indices.filter{match(value[it],rule,"$path/$it",budget)!=null};val count=matched.size.toBigDecimal()
                require(count>=(bound("minContains") ?: BigDecimal.ONE) && bound("maxContains")?.let{count<=it}!=false){"$path 包含项不符合要求"};marks.items+=matched}
            s["unevaluatedItems"]?.let{rule->value.forEachIndexed{i,item->if(i !in marks.items){visit(item,rule,"$path/$i",budget);marks.items+=i}}}
        }
        if(value is JsonPrimitive && value.isString){
            val text=value.content;size(text.codePointCount(0,text.length),"minLength","maxLength")
            s.string("pattern").takeIf(String::isNotBlank)?.let{require(Pattern.compile(it).matcher(text).find()){"$path 格式不匹配"}}
            val valid=when(s.string("format")){
                "email"->text.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                "uri"->runCatching{URI(text).isAbsolute}.getOrDefault(false)
                "uri-reference"->runCatching{URI(text)}.isSuccess
                "date"->runCatching{java.time.LocalDate.parse(text)}.isSuccess
                "date-time"->runCatching{java.time.OffsetDateTime.parse(text)}.isSuccess
                "time"->runCatching{java.time.OffsetTime.parse(text)}.isSuccess
                "duration"->runCatching{java.time.Duration.parse(text)}.isSuccess || runCatching{java.time.Period.parse(text)}.isSuccess
                "uuid"->text.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                "hostname"->text.length<=253 && text.removeSuffix(".").split('.').all{it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))}
                "ipv4"->text.split('.').let{it.size==4 && it.all{p->p.matches(Regex("0|[1-9][0-9]{0,2}")) && p.toInt()<=255}}
                "ipv6"->text.matches(Regex("[0-9A-Fa-f:.]+")) && runCatching{java.net.InetAddress.getByName(text) is java.net.Inet6Address}.getOrDefault(false)
                "regex"->runCatching{Pattern.compile(text)}.isSuccess
                "json-pointer"->text.isEmpty() || text.startsWith('/') && !Regex("~(?:[^01]|$)").containsMatchIn(text)
                else->true
            };require(valid){"$path 格式不正确"}
        }
        return marks
    }
}
