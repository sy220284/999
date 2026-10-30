package cn.codex.android

import android.content.Context
import android.net.LocalSocket
import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import android.os.CancellationSignal
import android.os.Process
import cn.codex.core.original.OriginalVerificationBridgeContract
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** 与凭证桥隔离，验证提示不阻塞账号刷新；只允许确切原版核心PID。 */
class OriginalVerificationServer(context:Context,parent:CoroutineScope):Closeable {
    val path=File(context.cacheDir,"verify-${UUID.randomUUID().toString().take(12)}.sock").absolutePath
    private val scope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job]))
    private val provider=OriginalAndroidVerification(context)
    private val bound=LocalSocket().apply{bind(LocalSocketAddress(path,LocalSocketAddress.Namespace.FILESYSTEM))}
    private val server=try{LocalServerSocket(bound.fileDescriptor)}catch(failure:Exception){bound.close();File(path).delete();throw failure}
    private val ready=CountDownLatch(1)
    @Volatile private var enginePid=-1
    private val closed=AtomicBoolean()
    private val admission=Semaphore(1)
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()
    private val signals=java.util.concurrent.ConcurrentHashMap.newKeySet<CancellationSignal>()
    init{scope.launch(Dispatchers.IO){
        while(isActive && !closed.get()){
            val socket=try{server.accept()}catch(_:IOException){break}
            socket.soTimeout=5000
            val peer=socket.peerCredentials
            if(!ready.await(10,TimeUnit.SECONDS) || peer.uid!=Process.myUid() || peer.pid!=enginePid || !admission.tryAcquire()){socket.close();continue}
            sockets+=socket
            launch{try{socket.use{handle(it)}}catch(_:Exception){ }finally{sockets-=socket;admission.release()}}
        }
    }}
    fun authorize(pid:Int){require(pid>0);check(enginePid<0);enginePid=pid;ready.countDown()}
    private suspend fun handle(socket:LocalSocket)=coroutineScope{
        val signal=CancellationSignal();signals+=signal
        val live=AtomicBoolean(true)
        try{
            val input=DataInputStream(socket.inputStream)
            val length=input.readInt();require(length in 1..OriginalVerificationBridgeContract.LIMIT)
            val bytes=ByteArray(length);input.readFully(bytes)
            val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val request=OriginalVerificationBridgeContract.request(Json.parseToJsonElement(text).jsonObject)
            socket.soTimeout=250
            val watcher=launch(Dispatchers.IO){
                while(isActive && live.get()){
                    try{input.read();live.set(false);signal.cancel();break} catch(_:java.net.SocketTimeoutException){ } catch(_:IOException){live.set(false);signal.cancel();break}
                }
            }
            try{
                val result=try{withTimeout(145_000){provider.handle(request,signal){live.get() && !closed.get() && !signal.isCanceled}}} catch(failure:VerificationBridgeFailure){buildJsonObject{put("error",failure.category)}} catch(_:TimeoutCancellationException){signal.cancel();buildJsonObject{put("error","timeout")}} catch(failure:CancellationException){throw failure} catch(_:Exception){buildJsonObject{put("error","providerError")}}
                if(live.get() && !closed.get()){
                    val encoded=result.toString().toByteArray(Charsets.UTF_8);require(encoded.size<=OriginalVerificationBridgeContract.LIMIT)
                    DataOutputStream(socket.outputStream).apply{writeInt(encoded.size);write(encoded);flush()}
                }
            }finally{live.set(false);signal.cancel();watcher.cancelAndJoin()}
        }finally{live.set(false);signal.cancel();signals-=signal}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        ready.countDown();signals.forEach{it.cancel()};signals.clear();scope.cancel()
        runCatching{server.close()};runCatching{bound.close()};sockets.forEach{runCatching{it.close()}};sockets.clear();File(path).delete()
    }
}
