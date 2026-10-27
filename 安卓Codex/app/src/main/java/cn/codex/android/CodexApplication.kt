package cn.codex.android

import android.app.Application
class CodexApplication: Application() {
    lateinit var toolPath: String
        private set
    override fun onCreate() {
        super.onCreate()
        toolPath = AndroidToolPaths.install(this)
        android.system.Os.setenv("PATH", toolPath, true)
    }
    val configurationJournal by lazy { ConfigurationJournal(this) }
    val gitOperations by lazy { GitNetworkController(this) }
    val fullTerminal by lazy { FullTerminalController(this) }
    val originalCore by lazy { OriginalCoreHost(this) }
    val controller by lazy { RuntimeController(this) }
}
