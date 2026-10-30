package cn.codex.android

import android.content.Context
import android.media.*
import cn.codex.core.original.OriginalWebRtcPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 单一媒体线程持有PeerConnection；原版RPC只传协商，音频直接经WebRTC传输。 */
class OriginalWebRtcAudio(private val context:Context,parent:CoroutineScope,private val failed:()->Unit) {
    data class Status(val threadId:String?=null,val preparing:Boolean=false,val ready:Boolean=false,val microphone:Boolean=false,val speaker:Boolean=false,val message:String="WebRTC尚未开始",val error:String?=null)
    private val dispatcher=Executors.newSingleThreadExecutor{r->Thread(r,"Codex-WebRTC")}.asCoroutineDispatcher()
    private val scope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job])+dispatcher)
    private val mutable=MutableStateFlow(Status());val state=mutable.asStateFlow()
    private var epoch=0L
    private var peer:PeerConnection?=null
    private var factory:PeerConnectionFactory?=null
    private var audio:JavaAudioDeviceModule?=null
    private var source:AudioSource?=null
    private var local:AudioTrack?=null
    private val remote=mutableListOf<AudioTrack>()
    private var channel:DataChannel?=null
    private var gathered:CompletableDeferred<Unit>?=null
    private var opened:CompletableDeferred<Unit>?=null
    private var answered=false
    private var answerBusy=false
    private val manager=context.getSystemService(AudioManager::class.java)
    private var oldMode:Int?=null
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener{change->if(change<0)scope.launch{controls(false,false);mutable.update{it.copy(message="音频焦点已失去，录音和播放已静音")}}}.build()
    init{scope.launch{try{awaitCancellation()}finally{release();dispatcher.close()}}}
    private fun failure(stamp:Long,text:String){if(stamp!=epoch)return;release();mutable.value=Status(message="媒体连接已结束",error=text);failed()}
    suspend fun prepare(thread:String):String=withTimeout(20_000){withContext(dispatcher){
        release();val stamp=epoch
        mutable.value=Status(threadId=thread,preparing=true,message="正在准备WebRTC音频；默认关闭麦克风与播放")
        try{
            initialize(context)
            audio=JavaAudioDeviceModule.builder(context)
                .setAudioRecordErrorCallback(object:JavaAudioDeviceModule.AudioRecordErrorCallback{
                    override fun onWebRtcAudioRecordInitError(errorMessage:String){scope.launch{failure(stamp,"麦克风初始化失败，已停止媒体")}}
                    override fun onWebRtcAudioRecordStartError(errorCode:JavaAudioDeviceModule.AudioRecordStartErrorCode,errorMessage:String){scope.launch{failure(stamp,"麦克风无法开始采集，已停止媒体")}}
                    override fun onWebRtcAudioRecordError(errorMessage:String){scope.launch{failure(stamp,"麦克风发生错误，已停止媒体")}}
                })
                .setAudioTrackErrorCallback(object:JavaAudioDeviceModule.AudioTrackErrorCallback{
                    override fun onWebRtcAudioTrackInitError(errorMessage:String){scope.launch{failure(stamp,"播放初始化失败，已停止媒体")}}
                    override fun onWebRtcAudioTrackStartError(errorCode:JavaAudioDeviceModule.AudioTrackStartErrorCode,errorMessage:String){scope.launch{failure(stamp,"无法开始播放，已停止媒体")}}
                    override fun onWebRtcAudioTrackError(errorMessage:String){scope.launch{failure(stamp,"播放发生错误，已停止媒体")}}
                }).createAudioDeviceModule().apply{setMicrophoneMute(true);setSpeakerMute(true)}
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(requireNotNull(audio)).createPeerConnectionFactory()
            source=factory!!.createAudioSource(MediaConstraints())
            local=factory!!.createAudioTrack("codex-microphone",requireNotNull(source)).apply{setEnabled(false)}
            val ready=CompletableDeferred<Unit>();gathered=ready;opened=CompletableDeferred()
            val observer=object:PeerConnection.Observer {
                override fun onSignalingChange(newState:PeerConnection.SignalingState){}
                override fun onIceConnectionChange(newState:PeerConnection.IceConnectionState){if(newState in setOf(PeerConnection.IceConnectionState.FAILED,PeerConnection.IceConnectionState.CLOSED))scope.launch{failure(stamp,"WebRTC连接已断开，请核查网络后重新开始")}}
                override fun onIceConnectionReceivingChange(receiving:Boolean){}
                override fun onIceGatheringChange(newState:PeerConnection.IceGatheringState){if(newState==PeerConnection.IceGatheringState.COMPLETE)scope.launch{if(stamp==epoch)ready.complete(Unit)}}
                override fun onIceCandidate(candidate:IceCandidate){}
                override fun onIceCandidatesRemoved(candidates:Array<out IceCandidate>){}
                override fun onAddStream(stream:MediaStream){}
                override fun onRemoveStream(stream:MediaStream){}
                override fun onDataChannel(dataChannel:DataChannel){scope.launch{dataChannel.close();dataChannel.dispose()}}
                override fun onRenegotiationNeeded(){}
                override fun onAddTrack(receiver:RtpReceiver,streams:Array<out MediaStream>){scope.launch{
                    if(stamp!=epoch)return@launch
                    (receiver.track() as? AudioTrack)?.let{track->track.setEnabled(state.value.speaker);if(track !in remote)remote+=track}
                }}
            }
            val config=PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply{sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN}
            peer=factory!!.createPeerConnection(config,observer) ?: error("无法创建媒体连接")
            // 静音不等于停止设备采集；协商前就禁用实际录音/播放。
            peer!!.setAudioRecording(false);peer!!.setAudioPlayout(false)
            peer!!.addTrack(requireNotNull(local),listOf("codex-audio"))
            channel=peer!!.createDataChannel("oai-events",DataChannel.Init().apply{ordered=true}) ?: error("无法创建媒体事件通道")
            channel!!.registerObserver(object:DataChannel.Observer{
                override fun onBufferedAmountChange(previousAmount:Long){}
                override fun onStateChange(){scope.launch{
                    if(stamp!=epoch)return@launch
                    when(channel?.state()){
                        DataChannel.State.OPEN->{opened?.complete(Unit);mutable.update{it.copy(ready=answered,message=if(answered)"媒体通道已连接，录音与播放由你手动开启" else "等待远端协商确认")}}
                        DataChannel.State.CLOSED,DataChannel.State.CLOSING->failure(stamp,"媒体事件通道已关闭")
                        else->Unit
                    }
                }}
                override fun onMessage(buffer:DataChannel.Buffer){if(buffer.data.remaining()>65536)scope.launch{failure(stamp,"媒体事件超出预算，已关闭连接")}}
            })
            val offer=createOffer(peer!!)
            description(peer!!,offer,true)
            if(peer!!.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE)ready.complete(Unit)
            withTimeout(15_000){ready.await()}
            check(stamp==epoch){"媒体准备已取消"}
            val result=OriginalWebRtcPolicy.sdp(peer!!.localDescription.description)
            mutable.update{it.copy(preparing=false,message="本机协商已准备，等待原版服务回应")};result
        }catch(failure:Throwable){if(stamp==epoch){release();mutable.value=Status(message="媒体准备未完成",error=if(failure is CancellationException)"媒体准备已取消" else "媒体准备失败，请核查权限和运行库")};throw failure}
    }}
    fun answer(thread:String,sdp:String){scope.launch{
        if(thread!=state.value.threadId || peer==null || answered || answerBusy)return@launch
        val stamp=epoch;answerBusy=true
        try{
            withTimeout(15_000){description(peer!!,SessionDescription(SessionDescription.Type.ANSWER,OriginalWebRtcPolicy.sdp(sdp)),false)
            if(stamp==epoch){answered=true;opened?.await();check(stamp==epoch);mutable.update{it.copy(ready=true,message="媒体通道已连接，默认静音")}}}
        }catch(error:Exception){if(error is CancellationException && error !is TimeoutCancellationException)throw error;failure(stamp,"远端音频协商失败，已停止媒体")}
        finally{if(stamp==epoch)answerBusy=false}
    }}
    fun setMicrophone(enabled:Boolean){scope.launch{controls(enabled,state.value.speaker)}}
    fun setSpeaker(enabled:Boolean){scope.launch{controls(state.value.microphone,enabled)}}
    private fun controls(microphone:Boolean,speaker:Boolean){
        if(!state.value.ready && (microphone || speaker))return
        try{
        if(microphone || speaker){
            if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){mutable.update{it.copy(error="未取得音频焦点，继续静音")};return}
            if(oldMode==null){oldMode=manager.mode;manager.mode=AudioManager.MODE_IN_COMMUNICATION}
        }
        audio?.setMicrophoneMute(!microphone);local?.setEnabled(microphone);peer?.setAudioRecording(microphone)
        audio?.setSpeakerMute(!speaker);remote.forEach{it.setEnabled(speaker)};peer?.setAudioPlayout(speaker)
        mutable.update{it.copy(microphone=microphone,speaker=speaker)}
        if(!microphone && !speaker)restoreAudioMode()
        }catch(_:Exception){failure(epoch,"音频设备控制失败，已关闭媒体连接")}
    }
    private fun restoreAudioMode(){manager.abandonAudioFocusRequest(focus);oldMode?.let{if(manager.mode==AudioManager.MODE_IN_COMMUNICATION)manager.mode=it};oldMode=null}
    fun close(){scope.launch{release();mutable.value=Status(message="WebRTC录音、播放和连接已关闭")}}
    private fun release(){
        epoch++;gathered?.cancel();gathered=null;opened?.cancel();opened=null;answered=false;answerBusy=false
        runCatching{peer?.setAudioRecording(false)};runCatching{peer?.setAudioPlayout(false)}
        runCatching{audio?.setMicrophoneMute(true)};runCatching{audio?.setSpeakerMute(true)};runCatching{local?.setEnabled(false)}
        remote.forEach{runCatching{it.setEnabled(false)}};remote.clear()
        runCatching{channel?.unregisterObserver()};runCatching{channel?.close()};runCatching{channel?.dispose()};channel=null
        runCatching{peer?.close()};runCatching{peer?.dispose()};peer=null
        runCatching{local?.dispose()};local=null;runCatching{source?.dispose()};source=null
        runCatching{factory?.dispose()};factory=null;runCatching{audio?.release()};audio=null;runCatching{restoreAudioMode()}
    }
    private suspend fun createOffer(pc:PeerConnection):SessionDescription=suspendCancellableCoroutine{c->
        pc.createOffer(object:SdpObserver{
            override fun onCreateSuccess(value:SessionDescription){if(c.isActive)c.resume(value)}
            override fun onCreateFailure(error:String){if(c.isActive)c.resumeWithException(IllegalStateException("协商创建失败"))}
            override fun onSetSuccess(){}
            override fun onSetFailure(error:String){}
        },MediaConstraints())
    }
    private suspend fun description(pc:PeerConnection,value:SessionDescription,local:Boolean):Unit=suspendCancellableCoroutine{c->
        val callback=object:SdpObserver{
            override fun onCreateSuccess(value:SessionDescription){}
            override fun onCreateFailure(error:String){}
            override fun onSetSuccess(){if(c.isActive)c.resume(Unit)}
            override fun onSetFailure(error:String){if(c.isActive)c.resumeWithException(IllegalStateException("协商设置失败"))}
        }
        if(local)pc.setLocalDescription(callback,value)else pc.setRemoteDescription(callback,value)
    }
    companion object {
        private var initialized=false
        @Synchronized private fun initialize(context:Context){if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).setEnableInternalTracer(false).createInitializationOptions());initialized=true}}
    }
}
