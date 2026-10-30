package cn.codex.core.original
import org.junit.Test
import kotlin.test.*
import kotlinx.serialization.json.*
class AndroidReleasePolicyTest {
    private fun release(digest:String="sha256:"+"a".repeat(64),url:String="https://github.com/sy220284/999/releases/download/v2/app.apk",size:Long=1024)=buildJsonObject{
        put("draft",false);put("prerelease",false);put("tag_name","v2");put("html_url","https://github.com/sy220284/999/releases/tag/v2")
        put("assets",buildJsonArray{add(buildJsonObject{put("name","app.apk");put("browser_download_url",url);put("size",size);put("digest",digest)})})
    }.toString()
    @Test fun onlyFixedRepositoryReleaseAssetsWithADigestAreOffered(){
        assertEquals(1,AndroidReleasePolicy.parse(release()).assets.size)
        assertTrue(AndroidReleasePolicy.parse(release(digest="")).assets.isEmpty())
        assertTrue(AndroidReleasePolicy.parse(release(url="https://github.com/other/repo/releases/download/v2/app.apk")).assets.isEmpty())
        assertTrue(AndroidReleasePolicy.parse(release(size=AndroidReleasePolicy.maximumApkBytes+1)).assets.isEmpty())
    }
    @Test fun redirectCannotDowngradeTlsOrLeakToAnotherHost(){
        assertTrue(AndroidReleasePolicy.downloadUrl("https://release-assets.githubusercontent.com/a?signature=test",true))
        assertFalse(AndroidReleasePolicy.downloadUrl("http://release-assets.githubusercontent.com/a",true))
        assertFalse(AndroidReleasePolicy.downloadUrl("https://github.com@attacker.invalid/a",true))
        assertFalse(AndroidReleasePolicy.downloadUrl("https://attacker.invalid/app.apk",true))
        assertFalse(AndroidReleasePolicy.downloadUrl("https://release-assets.githubusercontent.com/a",false))
    }
    @Test fun draftsAndPrereleasesCannotBecomeStableUpdates(){
        assertFailsWith<IllegalArgumentException>{AndroidReleasePolicy.parse(release().replace("\"draft\":false","\"draft\":true"))}
        assertFailsWith<IllegalArgumentException>{AndroidReleasePolicy.parse(release().replace("\"prerelease\":false","\"prerelease\":true"))}
    }
}
