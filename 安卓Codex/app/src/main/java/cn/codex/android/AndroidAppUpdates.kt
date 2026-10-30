package cn.codex.android
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class VerifiedAndroidUpdate(val id:String,val version:String,val versionCode:Long,val sha256:String,val abi:String)
data class AndroidUpdateState(val busy:Boolean=false,val message:String="尚未检查更新",val release:AndroidRelease?=null,val verified:VerifiedAndroidUpdate?=null,val error:String?=null)
/** 只有签名与当前安装包完全一致的新版本才交给系统安装器；不自行替换原生库。 */
class AndroidAppUpdates(private val context:Context,private val scope:CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)){
    private val mutable=MutableStateFlow(AndroidUpdateState());val state=mutable.asStateFlow()
    private var job:Job?=null;private var apk:File?=null
    private val directory=File(context.cacheDir,"已验证更新").apply{mkdirs()}
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(15,TimeUnit.MINUTES).build()
    private fun run(title:String,action:suspend()->Unit){
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,error=null,message=title)}
        job=scope.launch{try{action()}catch(error:CancellationException){mutable.update{it.copy(message="操作已停止，未启动安装")};throw error}catch(_:Exception){mutable.update{it.copy(error="更新检查或校验未通过，请核查网络、文件、包名、版本、ABI和签名；未启动安装")}}finally{mutable.update{it.copy(busy=false)}}}
    }
    private suspend fun <T> response(request:Request,consume:(Response)->T):T=coroutineScope{
        val call=client.newCall(request)
        val cancellation=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{call.cancel()}}
        try{withContext(Dispatchers.IO){call.execute().use(consume)}}finally{cancellation.cancel()}
    }
    fun check()=run("正在读取仓库正式发布"){
        val release=response(Request.Builder().url("https://api.github.com/repos/${AndroidReleasePolicy.repository}/releases/latest").header("Accept","application/vnd.github+json").build()){r->
            if(r.code==404)return@response null
            check(r.isSuccessful);val bytes=requireNotNull(r.body).byteStream().readNBytes(1024*1024+1);require(bytes.size<=1024*1024);AndroidReleasePolicy.parse(bytes.toString(Charsets.UTF_8))
        }
        mutable.update{it.copy(release=release,message=when{release==null->"仓库尚无可访问的正式发布；也可能需要仓库访问权限";release.assets.isEmpty()->"此发布没有带SHA256的独立APK，可选择本地签名包校验";else->"已读取正式发布，请选择APK下载并校验"})}
    }
    fun download(asset:AndroidReleaseAsset){
        if(state.value.release?.assets?.contains(asset)!=true)return
        run("正在下载并核对发布摘要"){
            val file=File(directory,UUID.randomUUID().toString()+".apk")
            val task=currentCoroutineContext()
            try{
                var url=asset.url;var done=false
                for(hop in 0..4){
                    check(AndroidReleasePolicy.downloadUrl(url,hop>0))
                    val next=response(Request.Builder().url(url).build()){r->
                        if(r.code in setOf(301,302,303,307,308)){check(hop<4);return@response requireNotNull(r.header("Location")).let{r.request.url.resolve(it)?.toString() ?: error("重定向无效")}}
                        check(r.isSuccessful);val body=requireNotNull(r.body);require(body.contentLength()<0 || body.contentLength()==asset.size)
                        body.byteStream().use{input->file.outputStream().use{out->copy(input,out,asset.size){task.ensureActive()}}};done=true;null
                    }
                    if(next==null)break;url=next
                }
                check(done && file.length()==asset.size)
                val verified=withContext(Dispatchers.IO){verify(file,asset.sha256)}
                apk?.delete();apk=file;mutable.update{it.copy(verified=verified,message="下载与包身份校验通过，等待你确认系统安装")}
            }finally{if(apk!=file)file.delete()}
        }
    }
    fun import(uri:Uri)=run("正在读取并校验本地签名APK"){
        val file=File(directory,UUID.randomUUID().toString()+".apk")
        val task=currentCoroutineContext()
        try{
            val verified=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);file.outputStream().use{out->copy(input,out,AndroidReleasePolicy.maximumApkBytes){task.ensureActive()}}};verify(file,null)}
            apk?.delete();apk=file;mutable.update{it.copy(verified=verified,message="包身份校验通过，等待你确认系统安装")}
        }finally{if(apk!=file)file.delete()}
    }
    private fun copy(input:InputStream,output:OutputStream,limit:Long,checkActive:()->Unit){var total=0L;val bytes=ByteArray(65536);while(true){checkActive();val n=input.read(bytes);if(n<0)break;total+=n;require(total<=limit && total<=AndroidReleasePolicy.maximumApkBytes);output.write(bytes,0,n)}}
    private fun sha(file:File):String{val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)}};return hash.digest().joinToString(""){"%02x".format(it)}}
    private fun verify(file:File,expected:String?):VerifiedAndroidUpdate{
        require(file.length() in 1..AndroidReleasePolicy.maximumApkBytes)
        val digest=sha(file);require(expected==null || digest==expected)
        val flags=PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val candidate=requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath,flags))
        require(candidate.packageName==context.packageName && candidate.longVersionCode>installed.longVersionCode)
        val info=requireNotNull(candidate.applicationInfo);require(info.minSdkVersion<=Build.VERSION.SDK_INT && info.targetSdkVersion>=37 && candidate.splitNames.isNullOrEmpty())
        fun signers(signing:android.content.pm.SigningInfo?)=requireNotNull(signing).apkContentsSigners.map{signature->MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}}.toSet().also{require(it.isNotEmpty())}
        require(signers(installed.signingInfo)==signers(candidate.signingInfo)){"签名不一致；证书轮换需另外审核，不自动接受历史证书"}
        val abi=ZipFile(file).use{zip->Build.SUPPORTED_ABIS.firstOrNull{arch->(zip.getEntry("lib/$arch/libcodex_engine.so")?.size ?: 0)>0 && (zip.getEntry("lib/$arch/libcodex_cli.so")?.size ?: 0)>0} ?: error("缺少当前ABI的完整核心")}
        return VerifiedAndroidUpdate(file.name.removeSuffix(".apk"),candidate.versionName.orEmpty(),candidate.longVersionCode,digest,abi)
    }
    suspend fun installationIntent(approved:VerifiedAndroidUpdate):Intent=withContext(Dispatchers.IO){
        check(!state.value.busy && state.value.verified===approved)
        val file=requireNotNull(apk);val current=verify(file,approved.sha256);require(current==approved)
        check(context.packageManager.canRequestPackageInstalls()){ "请先允许本应用请求安装未知来源应用" }
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun stop(){job?.cancel()}
    fun clear(){if(state.value.busy)return;apk?.delete();apk=null;mutable.update{it.copy(verified=null,message="已清除下载的更新文件")}}
}
