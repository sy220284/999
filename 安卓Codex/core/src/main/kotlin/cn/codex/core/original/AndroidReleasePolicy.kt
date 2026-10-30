package cn.codex.core.original
import java.net.URI
import kotlinx.serialization.json.*

data class AndroidReleaseAsset(val name:String,val url:String,val size:Long,val sha256:String)
data class AndroidRelease(val tag:String,val page:String,val assets:List<AndroidReleaseAsset>)
/** 固定仓库正式发布；远程资源必须提供GitHub的SHA256。安装前仍校验实际APK身份。 */
object AndroidReleasePolicy {
    const val repository="sy220284/999"
    const val maximumApkBytes=256L*1024*1024
    fun downloadUrl(value:String,redirect:Boolean=false):Boolean=runCatching{
        val uri=URI(value)
        uri.scheme=="https" && uri.rawUserInfo==null && uri.rawFragment==null && uri.port in setOf(-1,443) &&
            if(uri.host=="github.com")uri.path.startsWith("/$repository/releases/download/") && uri.rawQuery==null
            else redirect && uri.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com")
    }.getOrDefault(false)
    fun parse(text:String):AndroidRelease{
        require(text.toByteArray().size<=1024*1024)
        val json=Json.parseToJsonElement(text).jsonObject
        require(json["draft"]?.jsonPrimitive?.boolean==false && json["prerelease"]?.jsonPrimitive?.boolean==false)
        val tag=json.getValue("tag_name").jsonPrimitive.content;require(tag.isNotBlank() && tag.length<=128)
        val page=json.getValue("html_url").jsonPrimitive.content;val pageUri=URI(page)
        require(pageUri.scheme=="https" && pageUri.host=="github.com" && pageUri.rawUserInfo==null && pageUri.path.startsWith("/$repository/releases/tag/"))
        val rows=json.getValue("assets").jsonArray;require(rows.size<=100)
        val assets=rows.mapNotNull{item->val row=item.jsonObject
            val name=row.getValue("name").jsonPrimitive.content
            if(!name.endsWith(".apk",true))return@mapNotNull null
            val url=row.getValue("browser_download_url").jsonPrimitive.content;val size=row.getValue("size").jsonPrimitive.long
            val digest=row["digest"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if(!downloadUrl(url) || size !in 1..maximumApkBytes || !digest.matches(Regex("sha256:[a-fA-F0-9]{64}")))return@mapNotNull null
            AndroidReleaseAsset(name.take(256),url,size,digest.removePrefix("sha256:").lowercase())
        }
        return AndroidRelease(tag,page,assets)
    }
}
