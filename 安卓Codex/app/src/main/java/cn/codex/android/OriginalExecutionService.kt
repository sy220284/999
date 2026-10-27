package cn.codex.android

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class OriginalExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private val host get() = (application as CodexApplication).originalCore
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("original_execution", "原版核心任务", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { host.stop(); if (!running) stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val prompt = intent?.getStringExtra("prompt") ?: run { stopSelf(); return START_NOT_STICKY }
        val content = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 21, Intent(this, OriginalExecutionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "original_execution").setSmallIcon(R.drawable.ic_codex)
            .setContentTitle("原版 Codex 正在处理任务").setContentText("在手机上运行，点按返回查看进度").setContentIntent(content)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build())
        running = true
        scope.launch {
            try { host.runReserved(prompt) }
            finally { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { host.close(); stopSelf() }
    override fun onDestroy() { if (running) host.close(); scope.cancel(); super.onDestroy() }
}
