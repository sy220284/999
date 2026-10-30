package cn.codex.core.original
import cn.codex.core.string
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
class AgentOverviewOrderTest {
    private fun row(id:String,parent:String?=null,updated:Long=0,pin:Boolean=false)=buildJsonObject{put("id",id);put("updatedAt",updated);parent?.let{put("parentThreadId",it)};if(pin)put("section",buildJsonObject{put("id",OriginalAgentActions.PINNED)})}
    @Test fun rootFilterIncludesNestedDescendantsAndHiddenAncestorSuppressesWholeBranch(){
        val rows=listOf(row("root"),row("child","root"),row("grandchild","child"),row("other"))
        assertEquals(listOf("root","child","grandchild"),AgentOverviewOrder.visible(rows,emptySet(),true,"root",false).map{it.string("id")})
        assertEquals(listOf("root"),AgentOverviewOrder.visible(rows,setOf("child"),true,"root",false).map{it.string("id")})
    }
    @Test fun pinnedRowsStayAheadOfRecentRowsAndCyclesAreNotRoots(){
        val rows=listOf(row("recent",updated=30),row("pin",updated=1,pin=true),row("old",updated=2),row("cycle","cycle"))
        assertEquals(listOf("pin","recent","old"),AgentOverviewOrder.visible(rows,emptySet(),false,"",true).map{it.string("id")})
    }
}
