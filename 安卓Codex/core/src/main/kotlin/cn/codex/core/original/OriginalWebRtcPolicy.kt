package cn.codex.core.original

/** 与固定voice-host保持64KiB SDP、32候选上限；不解析或记录秘密ICE字段。 */
object OriginalWebRtcPolicy {
    fun sdp(value:String):String {
        require(value.toByteArray(Charsets.UTF_8).size in 1..65536 && '\u0000' !in value){"媒体协商内容超出限制"}
        val lines=value.lineSequence().filter{it.isNotBlank()}.toList()
        require(lines.size<=1024 && lines.firstOrNull()=="v=0"){"媒体协商格式不正确"}
        require(lines.count{it.startsWith("a=candidate:")}<=32){"媒体协商候选过多"}
        require(lines.any{it.startsWith("m=audio ")}){"协商没有音频通道"}
        return value
    }
}

/** 权限回调只授权发起点击时的会话，不能沿用到新会话或后台。 */
object OriginalRealtimePermission {
    fun allows(requestedThread:String?,currentThread:String?,foreground:Boolean)=foreground && !requestedThread.isNullOrBlank() && requestedThread==currentThread
}
