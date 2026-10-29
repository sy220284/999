package cn.codex.core.original
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test
class ManagedProjectRootsTest {
    @Test fun customManagedRootIsAcceptedButUnlistedSiblingIsRejected(){
        val temp=Files.createTempDirectory("managed-root").toFile()
        try{val normal=temp.resolve("normal").apply{mkdir()};val checkout=temp.resolve("custom/owned").apply{mkdirs()};val cwd=checkout.resolve("src").apply{mkdir()};val rows=listOf(buildJsonObject{put("root",checkout.path)})
            assertEquals(cwd.canonicalFile,ManagedProjectRoots.resolve(normal,cwd,rows));assertFailsWith<IllegalArgumentException>{ManagedProjectRoots.resolve(normal,temp.resolve("custom/sibling").apply{mkdir()},rows)}
        }finally{temp.deleteRecursively()}
    }
    @Test fun canonicalSymlinkEscapeCannotBecomeAnApprovedRoot(){
        val temp=Files.createTempDirectory("managed-link").toFile()
        try{val normal=temp.resolve("normal").apply{mkdir()};val other=temp.resolve("other").apply{mkdir()};val link=normal.resolve("link");Files.createSymbolicLink(link.toPath(),other.toPath());assertFailsWith<IllegalArgumentException>{ManagedProjectRoots.resolve(normal,link,emptyList())}}finally{temp.deleteRecursively()}
    }
}
