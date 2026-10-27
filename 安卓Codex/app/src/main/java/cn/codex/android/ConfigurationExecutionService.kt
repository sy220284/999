package cn.codex.android

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 不恢复请求；只有进程中仍存在的操作才能继续托管。 */
class ConfigurationExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as CodexApplication
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("configuration", "原版后台操作", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val content = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(3, Notification.Builder(this, "configuration").setSmallIcon(R.drawable.ic_codex).setContentTitle("正在处理${intent?.getStringExtra("kind") ?: "扩展配置"}")
            .setContentText("点按返回查看；系统中断后请核查实际状态").setContentIntent(content).setOngoing(true).build())
        scope.launch {
            app.configurationJournal.active.first { it == null }
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { app.originalCore.close(); stopSelf() }
    override fun onDestroy() {
        if (app.configurationJournal.active.value != null) app.originalCore.close()
        scope.cancel(); super.onDestroy()
    }
}
