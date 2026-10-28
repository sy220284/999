package cn.codex.android

import android.content.Context
import cn.codex.core.ApiConfig
import cn.codex.core.original.AppServerConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** 宿主只持有一个会话资源；测试可替换工厂，生产始终使用实际安装的原版程序。 */
data class OriginalEngineLaunch(val context: Context, val config: ApiConfig, val workspace: File, val home: File, val scope: CoroutineScope)
interface OriginalEngineSession : Closeable {
    val connection: AppServerConnection
}
fun interface OriginalEngineFactory {
    suspend fun open(launch: OriginalEngineLaunch): OriginalEngineSession
}

object NativeOriginalEngineFactory : OriginalEngineFactory {
    override suspend fun open(launch: OriginalEngineLaunch): OriginalEngineSession {
        var completed: OriginalEngineSession? = null
        try {
            return withContext(Dispatchers.IO) { create(launch).also { completed = it } }
        } catch (failure: Throwable) {
            // 结果返回主调度器时也可能取消；创建完成但未交出的资源仍由工厂回收。
            withContext(NonCancellable + Dispatchers.IO) { runCatching { completed?.close() } }
            throw failure
        }
    }
    private fun create(launch: OriginalEngineLaunch): OriginalEngineSession {
        val (context, config, workspace, home, scope) = launch
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcodex_engine.so")
        check(binary.isFile && binary.canExecute()) { "当前包未包含可执行的原版核心，请按运行核心目录的脚本生成对应 ABI 产物。" }
        AndroidLanguageRuntime.prepare(context)
        val endpoint = URI(config.endpoint)
        require(endpoint.scheme == "https" || config.allowLoopbackHttp && endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")) { "原版核心仅接受 HTTPS 或已允许的本机 HTTP 地址" }
        require(!endpoint.host.isNullOrBlank() && endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) { "模型地址需要有效主机名，且不可包含凭证、查询或片段" }
        val args = mutableListOf("--listen", "stdio://", "-c", "cli_auth_credentials_store=\"keyring\"", "-c", "mcp_oauth_credentials_store=\"keyring\"", "-c", "shell_environment_policy.inherit=\"core\"")
        // core 继承策略不含 GIT_*；仅显式传入工具位置，不扩大秘密环境继承范围。
        AndroidToolPaths.environment(context).forEach { (key, value) -> args += listOf("-c", "shell_environment_policy.set.$key=${JsonPrimitive(value)}") }
        // 原版 MCP 配置由管理页版本化保存，不再用每次启动的 CLI override 覆盖用户编辑。
        // 空文件仅首次创建；createNewFile 原子地保留已经存在的配置。
        File(home, "config.toml").createNewFile()
        val trust = AndroidTrustBundle.write(home)
        val vault = OriginalVaultServer(context, scope)
        var verification:OriginalVerificationServer?=null
        var child: NativeCoreProcess? = null
        var rpc: AppServerConnection? = null
        return try {
            val verifier=OriginalVerificationServer(context,scope).also{verification=it}
            val env = System.getenv().toMutableMap().apply {
                put("PATH", (context.applicationContext as CodexApplication).toolPath)
                putAll(AndroidToolPaths.environment(context))
                put("CODEX_HOME", home.absolutePath); put("HOME", home.absolutePath)
                put("CODEX_ANDROID_VERIFICATION_SOCKET",verifier.path)
                put("CODEX_ANDROID_VAULT_SOCKET", vault.path); put("CODEX_ANDROID_VAULT_HOST_PID", android.os.Process.myPid().toString())
                put("TMPDIR", context.cacheDir.absolutePath); put("SHELL", "/system/bin/sh")
                put("CODEX_CA_CERTIFICATE", trust.absolutePath); put("SSL_CERT_FILE", trust.absolutePath)
                remove("SSL_CERT_DIR"); remove("OPENAI_API_KEY"); remove("CODEX_API_KEY"); remove("CODEX_ANDROID_API_KEY")
            }
            if (config.endpoint.trimEnd('/') != "https://api.openai.com/v1") {
                val base = JsonPrimitive(config.endpoint.trimEnd('/')).toString()
                args += listOf("-c", "model_provider=\"android_service\"", "-c", "model_providers.android_service={name=\"手机模型服务\",base_url=$base,wire_api=\"responses\",env_key=\"CODEX_ANDROID_API_KEY\"}")
                if (config.apiKey.isNotBlank()) env["CODEX_ANDROID_API_KEY"] = config.apiKey
            }
            val process = NativeCoreProcess(binary, args, env, workspace).also { child = it }
            vault.authorize(process.pid);verifier.authorize(process.pid)
            scope.launch(Dispatchers.IO) { runCatching { process.errors.use { stream -> val bytes = ByteArray(4096); while (stream.read(bytes) >= 0) { /* 不记录可能携带凭证的 stderr */ } } } }
            val rpcConnection = AppServerConnection(process.input, process.output, scope, process::close).also { rpc = it }
            object : OriginalEngineSession {
                override val connection: AppServerConnection = rpcConnection
                private val closed = AtomicBoolean()
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    try { rpcConnection.close(); process.close() } finally { verifier.close();vault.close() }
                }
            }
        } catch (failure: Throwable) {
            runCatching { rpc?.close() }; runCatching { child?.close() }; runCatching { verification?.close() };runCatching { vault.close() }
            throw failure
        }
    }
}
