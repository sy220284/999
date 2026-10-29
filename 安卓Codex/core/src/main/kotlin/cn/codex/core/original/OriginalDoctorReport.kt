package cn.codex.core.original
import cn.codex.core.string
import kotlinx.serialization.json.*

data class OriginalDoctorCheck(val id:String,val category:String,val status:String,val summary:String,val raw:JsonObject){
    val title get()=OriginalDoctorReport.titles[id] ?: id
    val statusLabel get()=when(status){"ok"->"通过";"warning"->"需要留意";"fail"->"失败";else->"未知状态（$status）"}
    val explanation get()=when(id){
        "sandbox.helpers","sandbox.filesystem_paths"->"桌面沙箱检查不能证明手机项目已隔离。当前命令使用同一UID；请核对受限执行能力的实际结果。"
        "app_server.status"->"手机核心由前台服务托管，桌面守护进程探测结果不等于手机服务状态；请在本机连接服务页核查。"
        "updates.status","desktop.app.version"->"手机更新以签名APK和系统安装器为准；桌面安装器/应用商店更新检查不作为手机升级结果。"
        "desktop.app_server.handshake"->"这是桌面应用握手检查；手机连接请以本机核心初始化与当前连接结果为准。"
        "git.worktree.dev_drive"->"Windows Dev Drive专用检查，不适用于Android存储。"
        "terminal.env","terminal.title"->"独立诊断使用管道，交互终端使用PTY；终端颜色/标题/尺寸检查需结合具体入口理解。"
        "runtime.search"->"手机需要随包安装的对应ABI检索程序；仅有脚本或来源记录不能证明程序已安装。"
        "auth.credentials","auth.load"->"检查原版账号与Keystore桥的实际可读状态；手机连接偏好中的密钥不自动等于原版账号登录。"
        "network.provider_reachability","network.websocket_reachability"->"结果仅反映这次有界网络探测；不代表模型任务或账号权限已经可用。"
        else->"保留原版脱敏检查、明细和修复建议；应用不会自动执行报告里的修复命令。"
    }
}
data class OriginalDoctorReport(val generatedAt:String,val version:String,val status:String,val checks:List<OriginalDoctorCheck>){
    companion object {
        val titles=mapOf("installation" to "安装来源","app_server.status" to "后台核心","auth.credentials" to "账号凭证","auth.load" to "账号加载","config.load" to "原版配置","desktop.app.version" to "桌面应用版本","desktop.app_server.handshake" to "桌面握手","git.environment" to "Git环境","git.worktree.dev_drive" to "Windows开发盘","mcp.config" to "MCP配置","network.env" to "代理与网络环境","network.provider_reachability" to "模型服务连通性","network.websocket_reachability" to "WebSocket连通性","runtime.provenance" to "运行程序来源","runtime.search" to "文件检索程序","sandbox.filesystem_paths" to "文件路径边界","sandbox.helpers" to "沙箱支持","security.endpoint" to "端点防护","state.paths" to "数据目录","state.rollout_db_parity" to "会话与数据库一致性","system.disk" to "存储空间","system.environment" to "系统环境","terminal.env" to "终端能力","terminal.title" to "终端标题","updates.status" to "更新状态")
        fun parse(text:String):OriginalDoctorReport{
            require(text.toByteArray().size<=2*1024*1024);val root=Json.parseToJsonElement(text).jsonObject
            require(root["schemaVersion"]?.jsonPrimitive?.intOrNull==1){"诊断报告版本不支持，请查看原始报告"}
            val rows=root.getValue("checks").jsonObject;require(rows.size<=200)
            val checks=rows.map{(id,item)->val row=item.jsonObject;require(row.string("id")==id);OriginalDoctorCheck(id,row.string("category"),row.string("status"),row.string("summary"),row)}
            return OriginalDoctorReport(root.string("generatedAt"),root.string("codexVersion"),root.string("overallStatus"),checks)
        }
    }
}
