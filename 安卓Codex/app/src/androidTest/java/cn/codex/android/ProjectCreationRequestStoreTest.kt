package cn.codex.android

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 只提交测试源码；使用隔离目录，不触碰真实创建请求。 */
class ProjectCreationRequestStoreTest {
    @Test fun oldCompletionCannotClearANewerProjectRequest() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(base.cacheDir,"project-store-test-${UUID.randomUUID()}").apply{mkdirs()}
        val isolated=object:ContextWrapper(base){
            override fun getNoBackupFilesDir():File=directory
        }
        try{
            val store=ProjectCreationRequestStore(isolated)
            val first=buildJsonObject{put("key","first");put("payload",buildJsonObject{put("name","a")})}
            val second=buildJsonObject{put("key","second");put("payload",buildJsonObject{put("name","b")})}
            store.write(first);store.write(second);store.clear("first")
            assertEquals(second,store.read())
            store.clear("second");assertNull(store.read())
        }finally{directory.deleteRecursively()}
    }
}
