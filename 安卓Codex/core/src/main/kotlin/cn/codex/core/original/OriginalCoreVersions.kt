package cn.codex.core.original
object OriginalCoreVersions {
    private const val version="[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.+-]+)?"
    fun compare(cli:String,userAgent:String):String{
        val local=Regex("^(?:codex-cli|codex) ($version)(?:\\s.*)?$").matchEntire(cli.trim())?.groupValues?.get(1)
        val running=Regex("^[^/\\s]+/($version)(?:\\s.*)?$").matchEntire(userAgent.trim())?.groupValues?.get(1)
        return when{local==null || running==null->"版本格式无法识别，请核查原始信息";local==running->"运行核心与随包CLI版本号一致：$local";else->"版本号不一致：运行核心 $running，随包CLI $local；请结束旧核心并重新连接后核查"}
    }
}
