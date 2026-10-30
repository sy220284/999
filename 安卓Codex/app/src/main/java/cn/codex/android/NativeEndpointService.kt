package cn.codex.android
import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID
/** 多种本机服务共用通知；实例只关闭自己接管的资源，不影响新实例的待启动请求。 */
class NativeEndpointService:Service(){
    private val owner=UUID.randomUUID().toString()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val manager get()=(application as CodexApplication).nativeEndpoints
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("native_endpoints","本机连接服务",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val content=PendingIntent.getActivity(this,40,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,41,Intent(this,NativeEndpointService::class.java).setAction("stop_all"),PendingIntent.FLAG_IMMUTABLE)
        try{startForeground(4,Notification.Builder(this,"native_endpoints").setSmallIcon(R.drawable.ic_codex).setContentTitle("Codex本机连接服务")
            .setContentText("代理、隧道或共享核心运行中；点按返回核查").setContentIntent(content).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"停止本机服务",stop).build()).build())}catch(_:Exception){manager.rejectReserved(intent?.getStringExtra("lease").orEmpty(),"系统前台额度或权限未允许本次服务启动");manager.stopOwned(owner);stopSelfResult(startId);return START_NOT_STICKY}
        if(intent?.action=="stop_all")manager.stopAll()
        else if(!manager.startReserved(intent?.getStringExtra("lease").orEmpty(),owner)){if(!manager.running)stopSelfResult(startId);return START_NOT_STICKY}
        scope.launch{manager.state.first{rows->rows.values.none{it.running}};stopSelfResult(startId)}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){manager.stopOwned(owner);stopSelf()}
    override fun onDestroy(){manager.stopOwned(owner);scope.cancel();super.onDestroy()}
}
