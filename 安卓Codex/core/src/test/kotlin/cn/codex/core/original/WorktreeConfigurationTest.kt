package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class WorktreeConfigurationTest {
    @Test fun mergedDesktopAndBaseRemainLiteralData(){
        val desktop=buildJsonObject{put("git-worktree-root","/private/worktrees");put("worktree-auto-cleanup-enabled",true)}
        val config=WorktreeConfiguration.from(buildJsonObject{put("config",buildJsonObject{put("desktop",desktop)})})
        assertEquals(desktop,config.desktop);assertEquals("-feature",config.request("-feature")["base"]?.jsonPrimitive?.content)
        assertEquals(JsonNull,config.request(null)["base"])
    }
    @Test fun malformedConfigurationDoesNotUseDefaultRootSilently(){
        assertFailsWith<IllegalArgumentException>{WorktreeConfiguration.from(buildJsonObject{})}
        assertFailsWith<IllegalArgumentException>{WorktreeConfiguration.from(buildJsonObject{put("config",buildJsonObject{put("desktop",true)})})}
        assertFailsWith<IllegalArgumentException>{WorktreeConfiguration.from(buildJsonObject{put("config",buildJsonObject{})}).request("bad\nref")}
    }
}
