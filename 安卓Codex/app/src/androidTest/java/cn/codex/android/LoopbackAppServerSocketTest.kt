package cn.codex.android
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackAppServerSocketTest {
    /** 受控本机握手，不启动原版CLI/模型；该测试源码仍遵守本阶段不执行的约束。 */
    @Test fun freezesBearerAndMapsOneJsonLineToOneTextMessage()=runBlocking {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));val pool=Executors.newSingleThreadExecutor();val wire=LoopbackAppServerSocket()
        val headers=CompletableDeferred<Map<String,String>>();val received=CompletableDeferred<String>();val release=java.util.concurrent.CountDownLatch(1)
        val worker=pool.submit{server.accept().use{socket->socket.soTimeout=3000;val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
            fun line():String{val bytes=ByteArrayOutputStream();while(true){val b=input.read();check(b>=0 && bytes.size()<8192);if(b==10)return bytes.toString("UTF-8").removeSuffix("\r");bytes.write(b)}}
            check(line().startsWith("GET / HTTP/1.1"));val fields=mutableMapOf<String,String>();while(true){val row=line();if(row.isEmpty())break;val split=row.indexOf(':');check(split>0);fields[row.substring(0,split).lowercase()]=row.substring(split+1).trim()}
            headers.complete(fields)
            val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((fields.getValue("sec-websocket-key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            output.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n");output.flush()
            assertEquals(0x81,input.readUnsignedByte());val size=input.readUnsignedByte();assertTrue(size and 128 != 0);val length=size and 127;assertTrue(length<126)
            val mask=ByteArray(4);input.readFully(mask);val body=ByteArray(length);input.readFully(body);body.indices.forEach{i->body[i]=(body[i].toInt() xor mask[i%4].toInt()).toByte()};received.complete(body.toString(Charsets.UTF_8))
            val reply="{\"id\":1,\"result\":{}}".toByteArray();output.writeByte(0x81);output.writeByte(reply.size);output.write(reply);output.flush();release.await(3,TimeUnit.SECONDS)
        }}
        try{withTimeout(5000){wire.open("ws://127.0.0.1:${server.localPort}","a".repeat(43));withContext(Dispatchers.IO){wire.output.write("{\"id\":1}\n".toByteArray());wire.output.flush()}
            assertEquals("Bearer "+"a".repeat(43),headers.await()["authorization"]);assertNull(headers.await()["origin"]);assertEquals("{\"id\":1}",received.await())
            val line=withContext(Dispatchers.IO){wire.input.bufferedReader().readLine()};assertEquals("{\"id\":1,\"result\":{}}",line)}
        }finally{release.countDown();wire.close();server.close();worker.get(5,TimeUnit.SECONDS);pool.shutdownNow()}
    }
    @Test fun rejectsNonLiteralLoopbackAndCredentialInjectionBeforeConnecting()=runBlocking {
        for((url,token)in listOf("ws://localhost:1234" to "a".repeat(43),"ws://127.0.0.1:1234?token=x" to "a".repeat(43),"ws://127.0.0.1:1234" to "x\r\nAuthorization: other")){
            val wire=LoopbackAppServerSocket();try{try{wire.open(url,token);fail("不应允许不可信地址或头注入")}catch(_:IllegalArgumentException){}}finally{wire.close()}
        }
    }
}
