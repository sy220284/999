package cn.codex.android
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*
import java.io.File
class NativeCliProcessTest {
    @Test fun pollingKeepsExitStatusStableUntilOwnedProcessIsClosed()=runBlocking {
        val process=NativeCoreProcess(File("/system/bin/sh"),listOf("-c","exit 7"),System.getenv(),File("/"))
        try{withTimeout(3000){while(process.pollExit()==null)delay(10)};assertEquals(7,process.pollExit());assertEquals(7,process.pollExit())}finally{process.close()}
    }
}
