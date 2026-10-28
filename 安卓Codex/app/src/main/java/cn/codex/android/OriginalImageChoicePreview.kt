package cn.codex.android

import android.graphics.BitmapFactory
import android.webkit.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.codex.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.util.Base64

/** 图片不自动联网。内嵌SVG仅用户展开后在禁脚本/禁资源请求的独立视图显示。 */
@Composable fun OriginalImageChoicePreview(item:JsonObject) {
    val image=item.string("image");val context=LocalContext.current
    var expanded by remember(image){mutableStateOf(false)}
    val bitmap by produceState<android.graphics.Bitmap?>(null,image){value=withContext(Dispatchers.IO){runCatching{
        if(!image.startsWith("data:image/") || image.length>768*1024 || !image.substringBefore(',').endsWith(";base64") || "svg"in image.substringBefore(','))return@runCatching null
        val bytes=Base64.getDecoder().decode(image.substringAfter(','));val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong()*bounds.outHeight<=4_194_304)
        val options=BitmapFactory.Options().apply{inSampleSize=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/512)}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    }.getOrNull()}}
    bitmap?.let{Image(it.asImageBitmap(),contentDescription=item.string("title","服务提供的图片选项"),modifier=Modifier.fillMaxWidth().heightIn(max=180.dp))}
    if(image.startsWith("https://"))TextButton(onClick={openOriginalLogin(context,image)}){Text("在浏览器查看服务预览")}
    if(image.startsWith("data:image/svg+xml;base64,") && image.length<=256*1024){
        TextButton(onClick={expanded=!expanded}){Text(if(expanded)"关闭图片预览"else"展开服务图片预览")}
        if(expanded){
            val svg=remember(image){runCatching{Base64.getDecoder().decode(image.substringAfter(',')).toString(Charsets.UTF_8)}.getOrNull()}
            if(svg!=null && OriginalSvgPreviewPolicy.allowed(svg))AndroidView(
                factory={ctx->WebView(ctx).apply{
                    settings.javaScriptEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.blockNetworkLoads=true;settings.domStorageEnabled=false
                    settings.cacheMode=WebSettings.LOAD_NO_CACHE
                    webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    }
                    loadDataWithBaseURL(null,svg,"image/svg+xml","UTF-8",null)
                }},modifier=Modifier.fillMaxWidth().height(180.dp),onRelease={it.stopLoading();it.destroy()})
            else Text("此图片格式无法安全预览，可按选项名称选择。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

/** 只预览有界静态基本图形；不支持的SVG仍可按选项名称选择。 */
object OriginalSvgPreviewPolicy {
    fun allowed(svg:String):Boolean=runCatching{
        require(svg.toByteArray(Charsets.UTF_8).size<=128*1024 && !svg.contains("<!",true))
        val parser=android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(svg))
        val tags=setOf("svg","g","path","rect","circle","ellipse","line","polyline","polygon","text","tspan","title","desc")
        var nodes=0;var event=parser.eventType
        while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
            if(event==org.xmlpull.v1.XmlPullParser.START_TAG){
                require(++nodes<=200 && parser.depth<=12 && parser.name in tags)
                require(parser.namespace.orEmpty() in setOf("","http://www.w3.org/2000/svg"))
                if(parser.depth==1)require(parser.name=="svg")
                require(parser.attributeCount<=32)
                for(i in 0 until parser.attributeCount){
                    val name=parser.getAttributeName(i).lowercase();val value=parser.getAttributeValue(i)
                    require(!name.startsWith("on") && name !in setOf("href","src","style","filter","mask","clip-path") && value.length<=8192 && !value.contains("url(",true))
                }
            }
            require(event!=org.xmlpull.v1.XmlPullParser.DOCDECL && event!=org.xmlpull.v1.XmlPullParser.ENTITY_REF)
            event=parser.nextToken()
        }
        require(nodes>0);true
    }.getOrDefault(false)
}
