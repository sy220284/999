package cn.codex.android
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
class AndroidSandboxPolicyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun quote(text:String)="'"+text.replace("'","'\\''")+"'"
    @Test fun compatibleKernelAllowsProjectReadsAndDeniesOutsideContents()=runBlocking {
        assumeTrue(File(context.applicationInfo.nativeLibraryDir,"libcodex_sandbox.so").isFile)
        val root=File(context.cacheDir,"sandbox-${UUID.randomUUID()}").apply{mkdir()};val outside=File(context.cacheDir,"outside-${UUID.randomUUID()}").apply{writeText("outside-private-marker")}
        try{File(root,"inside").writeText("inside-allowed-marker");val runner=AndroidSandboxRunner(context,root);val probe=runner.execute(listOf("android-sandbox","probe"));assumeTrue(runCatching{Json.parseToJsonElement(probe.output.trim()).jsonObject.getValue("landlockAbi").jsonPrimitive.int}.getOrDefault(-1)>=6)
            val result=runner.execute(listOf("android-sandbox","run","/system/bin/cat inside && /system/bin/cat "+quote(outside.absolutePath)))
            assertTrue(result.output.contains("\"sandboxReady\":true"));assertTrue(result.output.contains("inside-allowed-marker"));assertFalse(result.output.contains("outside-private-marker"));assertTrue(result.exitCode!=0)
        }finally{root.deleteRecursively();outside.delete()}
    }
    @Test fun projectEscapeThroughSymlinksCannotReadAnotherProject()=runBlocking {
        assumeTrue(File(context.applicationInfo.nativeLibraryDir,"libcodex_sandbox.so").isFile)
        val root=File(context.cacheDir,"sandbox-${UUID.randomUUID()}").apply{mkdir()};val outside=File(context.cacheDir,"outside-${UUID.randomUUID()}").apply{writeText("symlink-private-marker")}
        try{java.nio.file.Files.createSymbolicLink(File(root,"link").toPath(),outside.toPath());val runner=AndroidSandboxRunner(context,root);val probe=runner.execute(listOf("android-sandbox","probe"));assumeTrue(runCatching{Json.parseToJsonElement(probe.output.trim()).jsonObject.getValue("landlockAbi").jsonPrimitive.int}.getOrDefault(-1)>=6)
            val result=runner.execute(listOf("android-sandbox","run","/system/bin/cat link"));assertTrue(result.output.contains("\"sandboxReady\":true"));assertFalse(result.output.contains("symlink-private-marker"));assertTrue(result.exitCode!=0)
        }finally{File(root,"link").delete();root.delete();outside.delete()}
    }
}
