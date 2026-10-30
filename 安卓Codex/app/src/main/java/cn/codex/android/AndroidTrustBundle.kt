package cn.codex.android

import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 把应用实际信任的 CA 传给 Rust HTTP/WebSocket；不关闭 TLS 校验，也不依赖桌面证书路径。 */
object AndroidTrustBundle {
    fun environment(bundle: File): Map<String, String> = mapOf(
        "SSL_CERT_FILE" to bundle.absolutePath,
        "CURL_CA_BUNDLE" to bundle.absolutePath,
        "GIT_SSL_CAINFO" to bundle.absolutePath)
    fun write(directory: File): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法准备证书目录" }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val roots = factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            .distinctBy { Base64.getEncoder().encodeToString(it.encoded) }
        check(roots.isNotEmpty() && roots.size <= 1000) { "无法获取 Android 信任证书，已停止启动以免产生不安全连接" }
        val encoder = Base64.getMimeEncoder(64, byteArrayOf(10))
        val pem = roots.joinToString("") { "-----BEGIN CERTIFICATE-----\n${encoder.encodeToString(it.encoded)}\n-----END CERTIFICATE-----\n" }
        val target = AtomicFile(File(directory, "android-trust.pem"))
        val stream = target.startWrite()
        try { stream.write(pem.toByteArray(Charsets.US_ASCII)); target.finishWrite(stream) }
        catch (failure: Exception) { target.failWrite(stream); throw failure }
        return target.baseFile
    }
}
