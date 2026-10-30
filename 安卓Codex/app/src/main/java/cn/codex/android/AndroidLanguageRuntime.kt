package cn.codex.android

import android.content.Context
import android.os.Build
import cn.codex.core.string
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** 只展开随安装包签名的标准库/JS数据；可执行程序始终位于nativeLibraryDir。 */
object AndroidLanguageRuntime {
    data class Status(val preparing:Boolean=true,val ready:Boolean=false,val message:String="正在检查内置开发环境",val error:String?=null)
    private val mutable=MutableStateFlow(Status());val state=mutable.asStateFlow()
    @Volatile private var cached:Map<String,String> = emptyMap()
    fun environment():Map<String,String> = cached
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun sha(input:InputStream):String {val digest=MessageDigest.getInstance("SHA-256");val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)};return digest.digest().joinToString(""){"%02x".format(it)}}
    private fun safe(value:String){require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value && value.split('/').none{it==".." || it=="." || it.isEmpty()}){"运行库路径非法"}}
    @Synchronized fun prepare(context:Context):Map<String,String> {
        if(state.value.ready)return cached
        mutable.value=Status()
        try{
            val trust=AndroidTrustBundle.environment(AndroidTrustBundle.write(File(context.filesDir,"原版工具/证书")))
            val abi=Build.SUPPORTED_ABIS.firstOrNull{it in setOf("arm64-v8a","x86_64")} ?: error("当前ABI没有运行库")
            val encoded=try{context.assets.open("runtimes/manifest-$abi.json").use{input->val bytes=input.readNBytes(8*1024*1024+1);require(bytes.size<=8*1024*1024);bytes}}catch(_:FileNotFoundException){
                cached=trust;mutable.value=Status(preparing=false,ready=true,message="当前包没有语言运行库，可继续使用系统shell；Python/Node/npm不会虚报已安装")
                return cached
            }
            val manifest=Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject;require(manifest.string("abi")==abi)
            val root=File(context.filesDir,"内置运行环境").apply{mkdirs()}.canonicalFile
            val result=trust.toMutableMap()
            val bundles=manifest.getValue("bundles").jsonArray;require(bundles.size<=2)
            val kinds=mutableSetOf<String>()
            for(value in bundles){
                val data=value.jsonObject;val kind=data.string("kind");require(kind in setOf("python","node") && kinds.add(kind))
                val archive=data.string("archive");safe(archive);require('/' !in archive)
                val digest=data.string("sha256");require(digest.matches(Regex("[a-f0-9]{64}")))
                context.assets.open("runtimes/$archive").use{require(sha(it)==digest){"运行库归档校验失败"}}
                val destination=File(root,"$kind-$abi-${digest.take(16)}")
                val receipt=File(destination,".complete")
                if(!receipt.isFile || receipt.readText()!=digest){
                    val temporary=File(root,"prepare-${UUID.randomUUID()}");check(temporary.mkdir())
                    try{
                        extract(context,archive,data.getValue("files").jsonObject,temporary)
                        File(temporary,".complete").writeText(digest)
                        check(!destination.exists()){ "运行库目录不完整，请先在文件页核查后移除该版本目录" }
                        check(temporary.renameTo(destination)){"运行库提交失败"}
                    }finally{if(temporary.exists())temporary.deleteRecursively()}
                }
                if(kind=="python")result["CODEX_ANDROID_PYTHON_HOME"]=destination.absolutePath
                else {result["CODEX_ANDROID_NODE_HOME"]=destination.absolutePath;result["npm_config_script_shell"]="/system/bin/sh"}
            }
            if(kinds.isNotEmpty())result["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir
            cached=result.toMap();mutable.value=Status(preparing=false,ready=true,message="内置开发环境数据已准备；实际解释器与扩展仍需设备验证")
            return cached
        }catch(failure:Exception){mutable.value=Status(preparing=false,ready=false,message="运行环境准备失败",error="运行库校验或展开失败，请核查包内资产与可用空间");throw failure}
    }
    private fun extract(context:Context,archive:String,files:JsonObject,destination:File){
        require(files.size<=50000);val seen=mutableSetOf<String>();var total=0L
        ZipInputStream(context.assets.open("runtimes/$archive")).use{zip->
            while(true){val entry=zip.nextEntry ?: break;safe(entry.name);require(!entry.isDirectory && seen.add(entry.name))
                val expected=files[entry.name]?.jsonObject ?: error("归档包含未登记文件")
                val limit=expected.getValue("size").jsonPrimitive.long;require(limit in 0..512L*1024*1024)
                val file=File(destination,entry.name).canonicalFile;require(file.path.startsWith(destination.canonicalPath+File.separator));file.parentFile!!.mkdirs()
                val digest=MessageDigest.getInstance("SHA-256");var count=0L
                file.outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=zip.read(buffer);if(n<0)break;count+=n;total+=n;require(count<=limit && total<=512L*1024*1024);digest.update(buffer,0,n);out.write(buffer,0,n)}}
                require(count==limit && digest.digest().joinToString(""){"%02x".format(it)}==expected.string("sha256")){"运行库文件校验失败"}
                zip.closeEntry()
            }
        }
        require(seen==files.keys){"运行库文件缺失"}
    }
}
