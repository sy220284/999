package cn.codex.android

import android.app.Application
class CodexApplication: Application() {
    val originalCore by lazy { OriginalCoreHost(this) }
    val controller by lazy { RuntimeController(this) }
}
