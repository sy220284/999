package cn.codex.android
import android.content.Context
import cn.codex.core.string
import java.io.*
import java.util.UUID
import java.math.BigDecimal
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** 固定标准库来自签名APK，脚本通过eval、模块和用户数据通过stdin；不从可写缓存加载代码。 */
object AndroidStandardSchema {
    @Volatile private var kernelCompatible:Boolean?=null
    suspend fun available(context:Context):Boolean=try{
        listOf("libcodex_node.so","libcodex_sandbox.so").all{File(context.applicationInfo.nativeLibraryDir,it).let{file->file.isFile && file.canExecute()}} && context.assets.list("schema-validator")?.contains("modules.json")==true && (kernelCompatible ?: probeKernel(context).also{kernelCompatible=it})
    }catch(cancel:CancellationException){throw cancel}catch(_:Exception){false}
    private suspend fun probeKernel(context:Context):Boolean=withContext(Dispatchers.IO){
        val child=NativeCoreProcess(File(context.applicationInfo.nativeLibraryDir,"libcodex_sandbox.so"),listOf("probe"),mapOf("PATH" to "/system/bin"),context.cacheDir)
        try{coroutineScope{
            val output=async{child.input.readNBytes(4097)};val errors=launch{child.errors.use{it.readNBytes(4097)}}
            try{withTimeoutOrNull(3000){child.output.close();var code=child.pollExit();while(code==null){delay(10);code=child.pollExit()};val bytes=output.await();errors.join();bytes.size<=4096 && code==0 && runCatching{Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["landlockAbi"]?.jsonPrimitive?.intOrNull?.let{it>=6}==true}.getOrDefault(false)} ?: false}finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
        }}finally{child.close()}
    }
    private fun numericBudget(node:JsonElement,depth:Int=0,count:IntArray=intArrayOf(0)){
        require(depth<=64 && ++count[0]<=50000){"标准表单超出结构预算"}
        when(node){
            is JsonObject->node.values.forEach{numericBudget(it,depth+1,count)}
            is JsonArray->node.forEach{numericBudget(it,depth+1,count)}
            is JsonPrimitive->if(!node.isString && node.booleanOrNull==null && node!=JsonNull){
                val exact=node.content.toBigDecimal();val number=node.content.toDouble()
                require(number.isFinite() && number.toString().toBigDecimal().compareTo(exact)==0 && exact.abs()<=BigDecimal("9007199254740991")){"数值超出标准库可无损表示的预算；未近似后批准"}
            }
        }
    }
    suspend fun validate(context:Context,schema:JsonObject,resources:List<JsonObject>,value:JsonObject)=withContext(Dispatchers.IO){
        check(available(context)){"标准表单校验产物缺失，未退回不完整验证"}
        require(schema.toString().toByteArray().size+resources.sumOf{it.toString().toByteArray().size}<=256*1024 && value.toString().toByteArray().size<=65536)
        numericBudget(schema);resources.forEach{numericBudget(it)};numericBudget(value)
        val bundle=context.assets.open("schema-validator/modules.json").use{it.readNBytes(8*1024*1024+1)};require(bundle.size<=8*1024*1024)
        val script=context.assets.open("schema-validator/standard-schema.cjs").use{it.readNBytes(65537)};require(script.size<=65536)
        val request=buildJsonObject{put("version",1);put("schema",schema);put("resources",JsonArray(resources));put("value",value)}
        val payload=("{\"request\":"+request+",\"bundle\":"+bundle.toString(Charsets.UTF_8)+"}").toByteArray();require(payload.size<=10*1024*1024)
        val directory=File(context.cacheDir,"schema-${UUID.randomUUID()}");check(directory.mkdir())
        var process:NativeCoreProcess?=null
        try{coroutineScope{
            val native=context.applicationInfo.nativeLibraryDir
            val args=listOf("exec",directory.path,native,directory.path,"--",File(native,"libcodex_node.so").path,"--max-old-space-size=96","--stack_size=512","--eval",script.toString(Charsets.UTF_8))
            val environment=mapOf("HOME" to directory.path,"TMPDIR" to directory.path,"PATH" to "$native:/system/bin","LD_LIBRARY_PATH" to native,"CODEX_SCHEMA_WORKER" to "1","LANG" to "C.UTF-8")
            val child=NativeCoreProcess(File(native,"libcodex_sandbox.so"),args,environment,directory).also{process=it}
            val output=async{child.input.readNBytes(8193).also{require(it.size<=8192)}}
            val errors=launch{child.errors.use{input->val block=ByteArray(4096);while(input.read(block)>=0){ }}}
            val writer=launch{child.output.use{it.write(payload);it.flush()}}
            try{withTimeout(5000){
                var code=child.pollExit();while(code==null){delay(10);code=child.pollExit()}
                val result=runCatching{Json.parseToJsonElement(output.await().toString(Charsets.UTF_8)).jsonObject}.getOrNull()
                check(code==0 && result?.get("valid")?.jsonPrimitive?.booleanOrNull==true){
                    val detail=result?.string("message")?.take(512) ?: result?.get("errors")?.toString()?.take(1024)
                    "标准规则校验未通过或运行条件不足，未批准：${detail ?: "请核查内核与标准库产物"}"
                }
                writer.join();errors.join()
            }}finally{withContext(NonCancellable+Dispatchers.IO){child.close()}}
        }}finally{withContext(NonCancellable+Dispatchers.IO){process?.close();payload.fill(0);if(directory.isDirectory)directory.listFiles()?.forEach{file->if(java.nio.file.Files.isRegularFile(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))file.delete()};directory.delete()}}
    }
}
