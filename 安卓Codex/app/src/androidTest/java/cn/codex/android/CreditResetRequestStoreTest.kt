package cn.codex.android

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.codex.core.original.OriginalCreditRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 仅测试本机幂等记录，未执行，不调用额度服务。 */
class CreditResetRequestStoreTest {
    @Test fun reopeningKeepsTheSameRequestUntilTheUserClearsIt() {
        val app=ApplicationProvider.getApplicationContext<CodexApplication>()
        val root=File(app.cacheDir,"credit-store-${System.nanoTime()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=root
        }
        try {
            val request=OriginalCreditRequest("fixed-request-key","credit-id")
            CreditResetRequestStore(context).write(request)
            assertEquals(request,CreditResetRequestStore(context).read())
            CreditResetRequestStore(context).clear()
            assertNull(CreditResetRequestStore(context).read())
        } finally { root.deleteRecursively() }
    }
}
