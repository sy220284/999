package cn.codex.android

import android.app.Application
import kotlinx.coroutines.*
class CodexApplication: Application() {
    lateinit var toolPath: String
        private set
    override fun onCreate() {
        super.onCreate()
        toolPath = AndroidToolPaths.install(this)
        android.system.Os.setenv("PATH", toolPath, true)
        AndroidToolPaths.environment(this).forEach { (key, value) -> android.system.Os.setenv(key, value, true) }
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{
            runCatching{AndroidLanguageRuntime.prepare(this@CodexApplication).forEach{(key,value)->android.system.Os.setenv(key,value,true)}}
        }
    }
    val configurationJournal by lazy { ConfigurationJournal(this) }
    val gitOperations by lazy { GitNetworkController(this) }
    val fullTerminal by lazy { FullTerminalController(this) }
    val originalCore by lazy { OriginalCoreHost(this) }
    val controller by lazy { RuntimeController(this) }
}
