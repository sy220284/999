package cn.codex.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicReference

/** 只在前台文字/语音页录音；权限由系统对话框申请，每次开始录音都由用户点击。 */
class OriginalRealtimeMicrophone(private val context:Context,private val scope:CoroutineScope,
    private val send:suspend(ByteArray)->Unit) {
    data class Status(val running:Boolean=false,val message:String="麦克风未开启")
    private val mutable=MutableStateFlow(Status())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private val active=AtomicReference<AudioRecord?>()
    fun start() {
        if(state.value.running)return
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){mutable.value=Status(message="需要先授予麦克风权限");return}
        mutable.value=Status(true,"正在录音，音频将发送到当前实时会话")
        job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val rate=24_000
                    val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    require(min>0){"设备不支持24kHz单声道录音"}
                    val recorder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(min,8192)).build()
                    active.set(recorder)
                    try {
                        check(recorder.state==AudioRecord.STATE_INITIALIZED){"麦克风初始化失败"}
                        coroutineContext.ensureActive();recorder.startRecording()
                        check(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING){"麦克风未能开始录音"}
                        val bytes=ByteArray(4096)
                        while(isActive){
                            val n=recorder.read(bytes,0,bytes.size,AudioRecord.READ_BLOCKING)
                            coroutineContext.ensureActive()
                            check(n>0 && n%2==0){"麦克风读取失败或帧不完整：$n"}
                            send(bytes.copyOf(n))
                        }
                    } finally {active.compareAndSet(recorder,null);runCatching{recorder.stop()};recorder.release()}
                }
            } catch(cancelled:CancellationException){throw cancelled} catch(failure:Exception){mutable.update{it.copy(message=failure.message ?: "录音已结束，请检查会话和权限")}} finally{mutable.update{it.copy(running=false,message=if(it.message.startsWith("正在录音"))"录音已停止" else it.message)}}
        }
    }
    fun stop(){job?.cancel();active.get()?.let{runCatching{it.stop()}}}
}
