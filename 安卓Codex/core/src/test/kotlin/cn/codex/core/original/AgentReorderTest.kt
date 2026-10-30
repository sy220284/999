package cn.codex.core.original
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
class AgentReorderTest {
    @Test fun reorderRequiresDistinctThreadsInTheSameActualSection(){
        fun row(id:String,section:String?)=buildJsonObject{put("id",id);if(section!=null)put("section",buildJsonObject{put("id",section)})}
        assertEquals("section",OriginalAgentActions.reorderSection(row("a","section"),row("b","section")))
        assertFailsWith<IllegalArgumentException>{OriginalAgentActions.reorderSection(row("a",null),row("b",null))}
        assertFailsWith<IllegalArgumentException>{OriginalAgentActions.reorderSection(row("a","one"),row("b","two"))}
        assertFailsWith<IllegalArgumentException>{OriginalAgentActions.reorderSection(row("a",null),row("a",null))}
    }
}
