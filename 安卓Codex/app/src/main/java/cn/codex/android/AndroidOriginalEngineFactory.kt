package cn.codex.android

import cn.codex.core.original.AppServerConnection
import cn.codex.core.original.NativeEndpointPlans
import cn.codex.core.original.NativeEndpointKind
import java.io.File
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.*
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 同一主界面连接stdio或本次手机前台共享核心；不自动恢复、重放或降级到第二个核心。 */
object AndroidOriginalEngineFactory:OriginalEngineFactory {
    override suspend fun open(launch:OriginalEngineLaunch):OriginalEngineSession {
        val manager=(launch.context.applicationContext as CodexApplication).nativeEndpoints
        val daemon=manager.daemonConnection(launch.config) ?: return NativeOriginalEngineFactory.open(launch)
        if(daemon.address.startsWith("unix://"))return unix(launch,manager,daemon)
        val wire=LoopbackAppServerSocket()
        var connection:AppServerConnection?=null
        try{
            wire.open(daemon.address,daemon.credential)
            currentCoroutineContext().ensureActive()
            val rpc=AppServerConnection(wire.input,wire.output,launch.scope,wire::close).also{connection=it}
            return object:OriginalEngineSession {
                override val connection=rpc
                private val closed=AtomicBoolean()
                override fun close(){if(!closed.compareAndSet(false,true))return;rpc.close();wire.close();manager.stop(cn.codex.core.original.NativeEndpointKind.DAEMON,daemon.id)}
            }
        }catch(failure:Throwable){connection?.close();wire.close();throw failure}
    }

    private suspend fun unix(launch:OriginalEngineLaunch,manager:NativeEndpoints,daemon:DaemonConnection):OriginalEngineSession{
        var process:NativeCoreProcess?=null;var rpc:AppServerConnection?=null;var completed:OriginalEngineSession?=null
        try{return withContext(Dispatchers.IO){
            val context=launch.context;val binary=File(context.applicationInfo.nativeLibraryDir,"libcodex_cli.so");check(binary.isFile && binary.canExecute()){"缺少原版Unix控制代理程序"}
            val environment=mapOf("PATH" to (context.applicationContext as CodexApplication).toolPath,"HOME" to launch.home.absolutePath,"CODEX_HOME" to launch.home.absolutePath,"TMPDIR" to context.cacheDir.absolutePath)
            val child=NativeCoreProcess(binary,NativeEndpointPlans.unixProxy(daemon.address.removePrefix("unix://")),environment,launch.workspace).also{process=it}
            launch.scope.launch(Dispatchers.IO){runCatching{child.errors.use{input->val bytes=ByteArray(8192);while(input.read(bytes)>=0){ }}}}
            val proxyConnection=AppServerConnection(child.input,child.output,launch.scope,child::close).also{rpc=it}
            object:OriginalEngineSession{
                override val connection=proxyConnection
                private val closed=AtomicBoolean()
                override fun close(){if(!closed.compareAndSet(false,true))return;proxyConnection.close();child.close();manager.stop(NativeEndpointKind.DAEMON,daemon.id)}
            }.also{completed=it}
        }}catch(failure:Throwable){withContext(NonCancellable+Dispatchers.IO){runCatching{completed?.close()};runCatching{rpc?.close()};runCatching{process?.close()}};throw failure}
    }
}

/** OkHttp接收完整WS消息后再核对16MiB帧预算；队列另有32MiB/64帧上限。 */
internal class LoopbackAppServerSocket:Closeable {
    private val limit=16*1024*1024
    private val closed=AtomicBoolean()
    private val opened=CompletableDeferred<Unit>()
    private val frames=ArrayBlockingQueue<ByteArray>(64)
    private val queued=AtomicLong()
    private val eof=ByteArray(0)
    private val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    @Volatile private var socket:WebSocket?=null
    val input:InputStream=object:InputStream(){
        private var current=ByteArray(0);private var at=0
        override fun read():Int{val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
        override fun read(bytes:ByteArray,offset:Int,length:Int):Int{
            java.util.Objects.checkFromIndexSize(offset,length,bytes.size);if(length==0)return 0
            while(at==current.size){if(closed.get())return -1;current=try{frames.take()}catch(error:InterruptedException){Thread.currentThread().interrupt();throw InterruptedIOException("共享核心读取中断")};at=0;if(current===eof)return -1;queued.addAndGet(-current.size.toLong())}
            val count=minOf(length,current.size-at);current.copyInto(bytes,offset,at,at+count);at+=count;return count
        }
        override fun close(){this@LoopbackAppServerSocket.close()}
    }
    val output:OutputStream=object:OutputStream(){
        private val frame=ByteArrayOutputStream()
        @Synchronized override fun write(value:Int){check(!closed.get()){ "共享核心已断开" };if(value==10){require(frame.size()>0);val text=frame.toString(Charsets.UTF_8.name());frame.reset();val ws=requireNotNull(socket);if(ws.queueSize()+text.toByteArray().size>32L*1024*1024 || !ws.send(text)){this@LoopbackAppServerSocket.close();throw IOException("共享核心发送队列已满")}}else{if(frame.size()>=limit){this@LoopbackAppServerSocket.close();throw IOException("共享核心请求超过帧预算")};frame.write(value)}}
        @Synchronized override fun write(bytes:ByteArray,offset:Int,length:Int){
            java.util.Objects.checkFromIndexSize(offset,length,bytes.size);check(!closed.get()){ "共享核心已断开" }
            var start=offset
            for(index in offset until offset+length)if(bytes[index].toInt()==10){append(bytes,start,index-start);write(10);start=index+1}
            append(bytes,start,offset+length-start)
        }
        private fun append(bytes:ByteArray,start:Int,count:Int){if(frame.size().toLong()+count>limit){this@LoopbackAppServerSocket.close();throw IOException("共享核心请求超过帧预算")};frame.write(bytes,start,count)}
        override fun close(){this@LoopbackAppServerSocket.close()}
    }
    suspend fun open(address:String,credential:String){
        val uri=URI(address);require(uri.scheme=="ws" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.isNullOrEmpty())
        require(credential.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        val request=Request.Builder().url(address).header("Authorization","Bearer $credential").build()
        socket=client.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){if(closed.get())webSocket.cancel()else opened.complete(Unit)}
            override fun onMessage(webSocket:WebSocket,text:String){val bytes=text.toByteArray(Charsets.UTF_8);if(bytes.isEmpty() || bytes.size>limit || text.indexOf('\n')>=0 || text.indexOf('\r')>=0){close();return};val line=bytes+byteArrayOf(10);val total=queued.addAndGet(line.size.toLong());if(total>32L*1024*1024 || !frames.offer(line)){queued.addAndGet(-line.size.toLong());close()}}
            override fun onMessage(webSocket:WebSocket,bytes:ByteString){close()}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(1000,null);close()}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){close()}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){opened.completeExceptionally(IOException("共享核心握手或连接失败"));close()}
        })
        try{withTimeout(15_000){opened.await()};check(!closed.get()){ "共享核心在握手后已关闭" }}catch(failure:Throwable){close();throw failure}
    }
    override fun close(){if(!closed.compareAndSet(false,true))return;opened.completeExceptionally(IOException("共享核心连接已关闭"));socket?.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();frames.clear();frames.offer(eof)}
}
