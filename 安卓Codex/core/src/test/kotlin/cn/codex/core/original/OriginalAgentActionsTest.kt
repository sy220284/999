package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class OriginalAgentActionsTest {
    private fun row(json:String)=Json.parseToJsonElement(json).jsonObject
    @Test fun pinAndRenameApprovalIgnoreLiveUsageButRejectMetadataChanges(){
        val before=row("""{"id":"t","name":"old","cwd":"/p","section":null,"updatedAt":1}""")
        OriginalAgentActions.verifyMetadata(before,row("""{"id":"t","name":"old","cwd":"/p","section":null,"updatedAt":2}"""))
        assertFailsWith<IllegalArgumentException>{OriginalAgentActions.verifyMetadata(before,row("""{"id":"t","name":"new","cwd":"/p","section":null}"""))}
    }
    @Test fun stopOnlyUsesTheExplicitInProgressTurn(){
        val turns=Json.parseToJsonElement("""[{"id":"old","status":"completed"},{"id":"active","status":"inProgress"}]""").jsonArray
        assertEquals("active",OriginalAgentActions.activeTurn(turns))
        assertNull(OriginalAgentActions.activeTurn(JsonArray(emptyList())))
        assertFailsWith<IllegalArgumentException>{OriginalAgentActions.activeTurn(JsonArray(listOf(turns[1],turns[1])))}
    }
}
