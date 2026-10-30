package cn.codex.android

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class ExecutionService: Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("execution", "任务运行状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as CodexApplication).controller
        if (intent?.action == "stop") { controller.stop(); if (!running) stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val prompt = intent?.getStringExtra("prompt") ?: run { stopSelf(); return START_NOT_STICKY }
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ExecutionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "execution").setSmallIcon(cn.codex.android.R.drawable.ic_codex).setContentTitle("Codex 正在处理任务").setContentText("任务在本机执行，点按返回查看进度").setContentIntent(content).addAction(Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build()
        startForeground(1, notification)
        running = true
        scope.launch {
            try { controller.run(prompt, intent.getBooleanExtra("terminal", false)) }
            finally { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { (application as CodexApplication).controller.stop(); stopSelf() }
}
