package cn.codex.android
import androidx.test.platform.app.InstrumentationRegistry
import cn.codex.core.original.NativeEndpointKind
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class NativeEndpointOwnershipTest {
    @Test fun pendingCancellationNeverSpawnsAndAnOldServiceCannotClaimIt()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CodexApplication
        val root=File(app.cacheDir,"endpoint-test-${UUID.randomUUID()}");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);var calls=0
        val manager=NativeEndpoints(app,scope,root,{_ -> Unit},EndpointProcessSpawner{_,_,_,_->calls++;error("未应启动进程")})
        try{withContext(Dispatchers.Main){manager.startExecutor(0);val id=requireNotNull(manager.state.value[NativeEndpointKind.EXECUTOR]).id
            manager.stopOwned("unrelated-owner");assertTrue(manager.running)
            manager.stop(NativeEndpointKind.EXECUTOR,id);assertFalse(manager.running);assertFalse(manager.startReserved(id,"late-service"));assertTrue(manager.pending.value.isEmpty())}
            assertEquals(0,calls)
        }finally{scope.cancel();root.deleteRecursively()}
    }
    @Test fun independentPendingKindsCoexistAndCredentialsAreNeverInRecoveryRecords()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CodexApplication
        val root=File(app.cacheDir,"endpoint-test-${UUID.randomUUID()}");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val manager=NativeEndpoints(app,scope,root,{_ -> Unit},EndpointProcessSpawner{_,_,_,_->error("未应启动进程")})
        try{withContext(Dispatchers.Main){manager.startExecutor(0);manager.startProxy("https://example.invalid/v1/responses","private-api-key",0)
            assertEquals(2,manager.state.value.values.count{it.running});assertEquals(2,manager.pending.value.size)
            val record=File(root,"服务操作记录.json").readText();assertFalse(record.contains("private-api-key"));assertFalse(record.contains("example.invalid"));assertFalse(record.contains("127.0.0.1"))
            manager.stopAll();assertFalse(manager.running);assertTrue(manager.pending.value.isEmpty())}
        }finally{scope.cancel();root.deleteRecursively()}
    }
    @Test fun corruptedRecoveryRecordBlocksStartUntilUserAcknowledges(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CodexApplication
        val root=File(app.cacheDir,"endpoint-test-${UUID.randomUUID()}").apply{mkdirs()}
        try{File(root,"服务操作记录.json").writeText("broken")
            val records=NativeEndpointRecords(root);assertNotNull(records.error)
            records.acknowledge(emptySet());assertNull(records.error);assertTrue(NativeEndpointRecords(root).entries.isEmpty())
        }finally{root.deleteRecursively()}
    }
}
