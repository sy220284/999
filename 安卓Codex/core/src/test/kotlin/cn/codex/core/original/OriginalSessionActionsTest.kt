package cn.codex.core.original

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 测试源码，当前未运行。 */
class OriginalSessionActionsTest {
    @Test fun gitPatchDistinguishesKeepClearAndReplaceWithoutTouchingOtherFields() {
        val patch = OriginalSessionActions.gitPatch(mapOf("sha" to OriginalMetadataEdit("keep", "ignored"), "branch" to OriginalMetadataEdit("clear"), "originUrl" to OriginalMetadataEdit("set", "https://example.invalid/repo.git")))
        assertFalse(patch.containsKey("sha")); assertEquals(JsonNull, patch["branch"])
        assertEquals("https://example.invalid/repo.git", patch.getValue("originUrl").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalSessionActions.gitPatch(mapOf("branch" to OriginalMetadataEdit("set", " "))) }
        assertFailsWith<IllegalArgumentException> { OriginalSessionActions.gitPatch(mapOf("sha" to OriginalMetadataEdit("keep"))) }
    }
    @Test fun reviewTargetsPreserveTheUserInputAndRejectEmptyScope() {
        assertEquals("uncommittedChanges", OriginalSessionActions.reviewTarget("uncommittedChanges").getValue("type").jsonPrimitive.content)
        assertEquals("origin/main", OriginalSessionActions.reviewTarget("baseBranch", "origin/main").getValue("branch").jsonPrimitive.content)
        val commit = OriginalSessionActions.reviewTarget("commit", "abc123", "修复说明")
        assertEquals("修复说明", commit.getValue("title").jsonPrimitive.content)
        assertEquals(" 检查资源回收\n", OriginalSessionActions.reviewTarget("custom", " 检查资源回收\n").getValue("instructions").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OriginalSessionActions.reviewTarget("custom", " ") }
        assertFailsWith<IllegalArgumentException> { OriginalSessionActions.reviewTarget("baseBranch", "") }
    }
    @Test fun repeatedPageCursorStopsWithoutMixingIdentities() {
        val initial = OriginalSessionPage<String>()
        val first = initial.append(listOf("thread-A"), "page-2", "first") { it }
        val repeated = first.append(listOf("thread-A", "thread-B"), "page-2", "page-2") { it }
        assertEquals(listOf("thread-A", "thread-B"), repeated.entries)
        assertNull(repeated.nextCursor); assertTrue(repeated.repeatedCursor)
    }
    @Test fun reviewModeHistoryDisplaysTheOriginalResultBody() {
        val result = OriginalThreadView.item(buildJsonObject { put("id", "review-1"); put("type", "exitedReviewMode"); put("review", "发现退出时资源未关闭") })
        assertEquals("发现退出时资源未关闭", result.text)
    }
}
