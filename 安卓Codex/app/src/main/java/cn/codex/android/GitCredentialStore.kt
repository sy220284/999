package cn.codex.android

import android.content.Context
import cn.codex.core.string
import kotlinx.serialization.json.*
import org.eclipse.jgit.transport.*
import java.net.URI

/** HTTPS 凭证按仓库来源隔离，由已有 Keystore 仓库加密落盘。 */
class GitCredentialStore(context: Context) {
    private val vault = OriginalCredentialVault(context)
    companion object {
        fun address(url: String): URI {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "请使用不含凭证、查询或片段的 HTTPS Git 地址" }
            return uri
        }
        private fun account(uri: URI) = URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, uri.path.trimEnd('/'), null, null).toString()
    }
    fun save(url: String, username: String, token: String) {
        val uri = address(url); require(username.isNotBlank() && token.isNotBlank()) { "请填写账号与访问令牌" }
        vault.save("codex-android-git", account(uri), buildJsonObject { put("username", username); put("token", token) }.toString())
    }
    fun remove(url: String) { vault.delete("codex-android-git", account(address(url))) }
    fun provider(url: String): CredentialsProvider? {
        val uri = address(url)
        val stored = vault.load("codex-android-git", account(uri)) ?: return null
        val data = Json.parseToJsonElement(stored).jsonObject
        val delegate = UsernamePasswordCredentialsProvider(data.string("username"), data.string("token"))
        return object : CredentialsProvider() {
            override fun isInteractive() = false
            override fun supports(vararg items: CredentialItem) = delegate.supports(*items)
            override fun get(target: URIish, vararg items: CredentialItem): Boolean {
                val sameOrigin = target.scheme == "https" && target.host.equals(uri.host, true) && (if (target.port == -1) 443 else target.port) == (if (uri.port == -1) 443 else uri.port)
                val root = uri.path.trimEnd('/')
                val path = target.path.orEmpty()
                return sameOrigin && (path == root || path.startsWith("$root/")) && delegate.get(target, *items)
            }
        }
    }
}
