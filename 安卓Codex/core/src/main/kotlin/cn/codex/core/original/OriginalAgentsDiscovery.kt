package cn.codex.core.original
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** 原版按两个来源保留游标与未展示行，按recency_at/updatedAt合并；每次每来源取一页。 */
class OriginalAgentsDiscovery {
    private class Source(val kinds:List<String>){
        var cursor:String?=null
        var exhausted=false
        var sort="recency_at"
        val rows=ArrayDeque<JsonObject>()
        val cursors=mutableSetOf<String>()
    }
    private val sources=listOf(Source(emptyList()),Source(listOf("exec","appServer")))
    data class Batch(val rows:List<JsonObject>,val more:Boolean,val failures:Int)
    private suspend fun fill(api:OriginalCodexClient,source:Source):Boolean{
        if(source.exhausted || source.rows.size>=10)return true
        while(true){
            try{
                val page=api.call("thread/list",buildJsonObject{put("limit",10);put("archived",false);put("useStateDbOnly",true);put("sortKey",source.sort);put("modelProviders",JsonArray(emptyList()));put("sourceKinds",JsonArray(source.kinds.map(::JsonPrimitive)));source.cursor?.let{put("cursor",it)}}).jsonObject
                val next=page.string("nextCursor").ifBlank{null};require(next==null || source.cursors.add(next)){"总览来源返回重复游标"}
                source.cursor=next;source.exhausted=next==null
                source.rows.addAll(page.getValue("data").jsonArray.map{it.jsonObject}.filter{visible(it)})
                return true
            }catch(cancel:CancellationException){throw cancel}catch(error:RpcException){
                if(source.sort=="recency_at" && error.code in setOf(-32600,-32602) && error.message.orEmpty().contains("recency_at")){source.sort="updated_at";source.cursor=null;source.cursors.clear()}else return false
            }
        }
    }
    suspend fun next(api:OriginalCodexClient,limit:Int=10):Batch=coroutineScope{
        require(limit in 1..100)
        val results=sources.map{source->async{fill(api,source)}}.awaitAll()
        val found=mutableListOf<JsonObject>()
        repeat(limit){
            val source=sources.filter{it.rows.isNotEmpty()}.maxWithOrNull(compareBy<Source>{recency(it.rows.first())}.thenBy{it.rows.first().string("id")}) ?: return@repeat
            found+=source.rows.removeFirst()
        }
        Batch(found,sources.any{!it.exhausted || it.rows.isNotEmpty()},results.count{!it})
    }
    companion object{
        fun visible(row:JsonObject)=row["ephemeral"]?.jsonPrimitive?.booleanOrNull!=true && row.string("parentThreadId").isBlank() && (((row["source"] as? JsonObject)?.get("subagent") as? JsonObject)?.containsKey("thread_spawn")!=true)
        private fun recency(row:JsonObject)=row["recencyAt"]?.jsonPrimitive?.longOrNull ?: row["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
    }
}
