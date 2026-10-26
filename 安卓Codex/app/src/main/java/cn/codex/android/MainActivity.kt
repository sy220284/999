package cn.codex.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity: ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as CodexApplication).controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val dark = if (state.config.theme == "system") isSystemInDarkTheme() else state.config.theme == "dark"
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CodexTheme(state.config.theme) {
                CodexApp(controller, state) { prompt, terminal ->
                    if (controller.reserveTask()) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        runCatching { startForegroundService(Intent(this, ExecutionService::class.java).putExtra("prompt", prompt).putExtra("terminal", terminal)) }.onFailure { controller.releaseReservation(); controller.fail("任务启动失败：${it.message}") }
                    }
                }
            }
        }
    }
}
