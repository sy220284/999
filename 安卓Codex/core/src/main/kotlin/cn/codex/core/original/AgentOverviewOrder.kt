package cn.codex.core.original
import cn.codex.core.string
import kotlinx.serialization.json.*
object AgentOverviewOrder {
    fun visible(rows:List<JsonObject>,hidden:Set<String>,children:Boolean,root:String,newest:Boolean):List<JsonObject>{
        require(rows.size<=400)
        val unique=rows.distinctBy{it.string("id")};val byId=unique.associateBy{it.string("id")}
        return unique.filter{row->
            val id=row.string("id");if(id.isBlank() || !children && row.string("parentThreadId").isNotBlank())return@filter false
            var current:String?=id;val seen=mutableSetOf<String>();var matches=root.isBlank()
            while(!current.isNullOrBlank()){
                if(!seen.add(current) || current in hidden)return@filter false
                if(current==root)matches=true
                current=byId[current]?.string("parentThreadId")
            }
            matches
        }.sortedWith(compareByDescending<JsonObject>{(it["section"] as? JsonObject)?.string("id")==OriginalAgentActions.PINNED}.thenByDescending{if(newest)it["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L else 0L})
    }
}
