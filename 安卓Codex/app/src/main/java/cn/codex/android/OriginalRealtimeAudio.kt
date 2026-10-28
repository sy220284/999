package cn.codex.android

import android.content.Context
import android.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import cn.codex.core.string
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** 用户开启后才播放；有界队列，播放器不占用协议读取线程。仅处理原版PCM16输出，未接WebRTC。 */
class OriginalRealtimeAudio(context: Context, scope: CoroutineScope) {
    data class Status(val enabled: Boolean = false, val message: String = "音频默认静音")
    private data class Packet(val epoch: Long, val rate: Int, val channels: Int, val bytes: ByteArray)
    private val mutable = MutableStateFlow(Status())
    val state = mutable.asStateFlow()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener { if(it < 0) stop("音频焦点已丢失，已静音") }.build()
    private val queue = Channel<Packet>(8)
    private val active = AtomicReference<AudioTrack?>()
    @Volatile private var epoch = 0L
    fun enable() {
        if(manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { mutable.value = Status(message = "未取得音频焦点，请稍后重试");return }
        epoch++;mutable.value = Status(true,"正在播放此页接收的实时音频")
    }
    fun stop(message: String = "已静音") {
        epoch++;mutable.value = Status(false,message)
        while(queue.tryReceive().isSuccess) { }
        active.get()?.let { runCatching { it.pause();it.flush() } }
        manager.abandonAudioFocusRequest(focus)
    }
    fun offer(audio: JsonObject) {
        if(!state.value.enabled)return
        try {
            val rate = (audio["sampleRate"] as? JsonPrimitive)?.intOrNull ?: error("缺少音频采样率")
            val channels = (audio["numChannels"] as? JsonPrimitive)?.intOrNull ?: error("缺少声道数")
            require(rate in 8000..192000 && channels in 1..2) { "当前音频格式不能播放" }
            val bytes = Base64.getDecoder().decode(audio.string("data"))
            require(bytes.size % (channels*2) == 0) { "PCM16音频帧不完整" }
            val stamp = epoch
            for(start in bytes.indices step 8192) {
                if(!queue.trySend(Packet(stamp,rate,channels,bytes.copyOfRange(start,minOf(start+8192,bytes.size)))).isSuccess) {
                    stop("音频接收过快，已静音；可再次开启");return
                }
            }
        } catch(failure: Exception) { stop(failure.message ?: "音频解析失败，已静音") }
    }
    init {
        scope.launch(Dispatchers.IO) {
            var format = ""
            try {
                for(packet in queue) {
                    if(packet.epoch != epoch || !state.value.enabled)continue
                    try {
                        val nextFormat = "${packet.rate}/${packet.channels}"
                        var track = active.get()
                        if(track == null || format != nextFormat) {
                            active.getAndSet(null)?.release()
                            val mask = if(packet.channels == 1)AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                            val min = AudioTrack.getMinBufferSize(packet.rate,mask,AudioFormat.ENCODING_PCM_16BIT)
                            require(min > 0) { "设备不支持此音频格式" }
                            track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(packet.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min,8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
                            active.set(track);format = nextFormat
                        }
                        if(packet.epoch != epoch || !state.value.enabled)continue
                        val player = requireNotNull(track)
                        player.play()
                        var offset = 0
                        while(offset < packet.bytes.size && packet.epoch == epoch && state.value.enabled) {
                            val written = player.write(packet.bytes,offset,packet.bytes.size-offset,AudioTrack.WRITE_BLOCKING)
                            check(written > 0) { "音频设备写入失败：$written" };offset+=written
                        }
                    } catch(failure: Exception) { active.getAndSet(null)?.release();stop(failure.message ?: "播放失败，已静音") }
                }
            } finally { active.getAndSet(null)?.release();manager.abandonAudioFocusRequest(focus) }
        }
    }
}
