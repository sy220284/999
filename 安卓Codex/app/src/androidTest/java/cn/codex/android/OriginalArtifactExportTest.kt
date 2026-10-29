package cn.codex.android
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
class OriginalArtifactExportTest {
    @Test fun exportsNestedFilesButRefusesLinksAndOtherFolders(){
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"export-test-${UUID.randomUUID()}").apply{mkdirs()}
        try{val source=File(root,"owned").apply{mkdir()};File(source,"nested").mkdir();File(source,"nested/schema.json").writeText("{}")
            val out=ByteArrayOutputStream();OriginalArtifactExport.write(root,source,out)
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use{zip->assertEquals("nested/schema.json",zip.nextEntry.name);assertEquals("{}",zip.readBytes().toString(Charsets.UTF_8));assertNull(zip.nextEntry)}
            Files.createSymbolicLink(File(source,"link").toPath(),File(source,"nested/schema.json").toPath())
            try{OriginalArtifactExport.write(root,source,ByteArrayOutputStream());fail("链接必须被拒绝")}catch(_:IllegalArgumentException){}
            try{OriginalArtifactExport.write(source,root,ByteArrayOutputStream());fail("越界目录必须被拒绝")}catch(_:IllegalArgumentException){}
        }finally{root.deleteRecursively()}
    }
}
