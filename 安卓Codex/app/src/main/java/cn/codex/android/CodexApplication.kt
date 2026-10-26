package cn.codex.android

import android.app.Application
class CodexApplication: Application() {
    val controller by lazy { RuntimeController(this) }
}
