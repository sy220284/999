package cn.codex.core.original

import cn.codex.core.Workspace
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

data class OriginalFileDocument(val path: String, val dataBase64: String, val text: String?)
data class OriginalFilesState(
    val connected: Boolean = false, val busy: Boolean = false, val mutating: Boolean = false,
    val directory: String = "", val entries: List<JsonObject> = emptyList(), val metadata: JsonObject? = null,
    val metadataPath: String = "", val document: OriginalFileDocument? = null,
    val watchId: String? = null, val watchPath: String? = null, val watchConfirmed: Boolean = false,
    val changedPaths: List<String> = emptyList(), val error: String? = null, val notice: String = "尚未读取目录"
)
object OriginalFileData {
    const val maxBytes = 2 * 1024 * 1024
    fun path(root: File, path: String, destructive: Boolean = false): String {
        val resolved = Workspace(root).resolve(path)
        require(!destructive || resolved != root.canonicalFile) { "不能删除或覆盖项目根目录" }
        val lexical = if (File(path).isAbsolute) File(path) else File(root, path)
        return lexical.toPath().toAbsolutePath().normalize().toString()
    }
    fun payload(response: JsonObject): String {
        val value = response["dataBase64"] as? JsonPrimitive
        require(value != null && value.isString && value.content.length <= ((maxBytes + 2) / 3) * 4) { "文件响应缺少有效正文或超过 2 MiB" }
        return value.content
    }
    fun text(base64: String): String? {
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size <= maxBytes) { "文件超过 2 MiB，请使用终端或系统文件工具处理" }
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().takeUnless { '\u0000' in it } }.getOrNull()
    }
    fun requireUnchanged(before: String, actual: String) { require(before == actual) { "文件已被其他操作修改，请重新读取并合并；没有覆盖文件" } }
    fun changed(state: OriginalFilesState, params: JsonObject): OriginalFilesState {
        if (state.watchId == null || state.watchId != params.string("watchId")) return state
        val paths = (params["changedPaths"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return state.copy(changedPaths = (paths + state.changedPaths).distinct().take(50))
    }
}
class OriginalFilesController(private val scope: CoroutineScope, val root: File, private val activeTask: () -> Boolean,
    private val lifecycle: OriginalMutationLifecycle = OriginalMutationLifecycle.None) {
    private val mutable = MutableStateFlow(OriginalFilesState(directory = root.absolutePath))
    val state = mutable.asStateFlow()
    private var client: OriginalCodexClient? = null
    private var generation = 0L
    private var job: Job? = null
    fun bind(api: OriginalCodexClient?) { generation++; job?.cancel(); client = api; mutable.value = OriginalFilesState(connected = api != null, directory = root.absolutePath) }
    fun changed(params: JsonObject) { mutable.update { OriginalFileData.changed(it, params) } }
    fun closeDocument() { if (!state.value.busy) mutable.update { it.copy(document = null) } }
    private fun operation(mutating: Boolean = false, action: suspend (OriginalCodexClient, Long) -> Unit) {
        if (state.value.busy) return
        val api = client ?: return
        if (mutating && activeTask()) { mutable.update { it.copy(error = "请先结束当前任务或其他变更") }; return }
        val stamp = generation
        val ticket = if (mutating) try { lifecycle.begin() } catch (failure: Exception) { mutable.update { it.copy(error = "无法托管文件变更：${failure.message.orEmpty()}") }; return } else null
        mutable.update { it.copy(busy = true, mutating = mutating, error = null) }
        job = scope.launch {
            var confirmed = false
            try { action(api, stamp); confirmed = stamp == generation }
            catch (failure: CancellationException) { if (failure !is TimeoutCancellationException) throw failure; if (stamp == generation) mutable.update { it.copy(error = "文件请求超时，请核查实际文件；不会自动重试") } }
            catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = failure.message ?: "文件操作失败") } }
            finally {
                try { if (ticket != null) lifecycle.finish(ticket, confirmed && stamp == generation) }
                catch (failure: Exception) { if (stamp == generation) mutable.update { it.copy(error = "操作记录未能保存，请核查实际文件") } }
                finally { if (stamp == generation) mutable.update { it.copy(busy = false, mutating = false) } }
            }
        }
    }
    private suspend fun list(api: OriginalCodexClient, stamp: Long, path: String) {
        val response = api.readDirectory(path).jsonObject
        val entries = response["entries"]?.jsonArray?.map { it.jsonObject } ?: error("目录响应缺少 entries")
        require(entries.all { val name = it.string("fileName"); name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\u0000' !in name }) { "目录包含无效的直接子项名称" }
        if (stamp == generation) mutable.update { it.copy(directory = path, entries = entries.sortedWith(compareByDescending<JsonObject> { it["isDirectory"]?.jsonPrimitive?.booleanOrNull == true }.thenBy { it.string("fileName") }), notice = "已读取 ${entries.size} 项") }
    }
    fun refresh(path: String = state.value.directory) = operation { api, stamp -> list(api, stamp, OriginalFileData.path(root, path)) }
    fun metadata(path: String) = operation { api, stamp -> val safe = OriginalFileData.path(root, path); val response = api.fileMetadata(safe).jsonObject; if (stamp == generation) mutable.update { it.copy(metadata = response, metadataPath = safe) } }
    fun read(path: String) = operation { api, stamp ->
        val safe = OriginalFileData.path(root, path)
        require(File(safe).length() <= OriginalFileData.maxBytes) { "文件超过 2 MiB，请使用终端或系统文件工具处理" }
        val base64 = OriginalFileData.payload(api.readFile(safe).jsonObject)
        val text = withContext(Dispatchers.Default) { OriginalFileData.text(base64) }
        if (stamp == generation) mutable.update { it.copy(document = OriginalFileDocument(safe, base64, text), notice = if (text == null) "二进制或非 UTF-8 文件，只能导出" else "已读取 UTF-8 文件") }
    }
    fun write(expected: OriginalFileDocument?, path: String, text: String) = operation(mutating = true) { api, stamp ->
        val safe = OriginalFileData.path(root, path, destructive = true)
        val bytes = withContext(Dispatchers.Default) { text.toByteArray(Charsets.UTF_8) }; require(bytes.size <= OriginalFileData.maxBytes) { "单次写入超过 2 MiB" }
        if (expected != null) {
            require(expected.path == safe && expected.text != null) { "文件已切换或不是可编辑的 UTF-8 文件" }
            val actual = OriginalFileData.payload(api.readFile(safe).jsonObject)
            OriginalFileData.requireUnchanged(expected.dataBase64, actual)
        } else require(!File(safe).exists()) { "目标已存在，请先打开文件核对后编辑" }
        val base64 = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(bytes) }
        api.writeFile(safe, base64)
        if (stamp == generation) mutable.update { it.copy(document = OriginalFileDocument(safe, base64, text), notice = "核心已确认写入；RPC 不提供原子版本比较，外部并发修改仍须核查") }
        list(api, stamp, state.value.directory)
    }
    fun mkdir(path: String) = operation(mutating = true) { api, stamp -> api.createDirectory(OriginalFileData.path(root, path), true); list(api, stamp, state.value.directory) }
    fun remove(path: String, recursive: Boolean) = operation(mutating = true) { api, stamp ->
        val safe = OriginalFileData.path(root, path, destructive = true)
        api.removeFile(safe, recursive)
        if (stamp == generation) mutable.update { it.copy(document = it.document?.takeUnless { document -> document.path == safe || document.path.startsWith("$safe/") }, notice = "核心已确认删除") }
        list(api, stamp, state.value.directory)
    }
    fun copy(source: String, destination: String, recursive: Boolean) = operation(mutating = true) { api, stamp ->
        val from = OriginalFileData.path(root, source); val to = OriginalFileData.path(root, destination, destructive = true)
        require(!File(to).exists()) { "目标已存在，请使用其他名称，避免覆盖" }
        require(!File(to).canonicalFile.toPath().startsWith(File(from).canonicalFile.toPath())) { "不能复制到源路径本身或其子目录" }
        api.copyFile(from, to, recursive); list(api, stamp, state.value.directory)
    }
    fun watch() = operation { api, stamp ->
        require(state.value.watchId == null) { "请先停止已有监听" }
        val path = OriginalFileData.path(root, state.value.directory); val id = UUID.randomUUID().toString()
        mutable.update { it.copy(watchId = id, watchPath = path, watchConfirmed = false, changedPaths = emptyList()) }
        val response = api.watchFiles(id, path).jsonObject
        if (stamp == generation) mutable.update { it.copy(watchConfirmed = true, watchPath = response.string("path"), notice = "已开始监听；离开页面仍持续，关闭核心后结束") }
    }
    fun unwatch() {
        val id = state.value.watchId ?: return
        operation { api, stamp -> api.unwatchFiles(id); if (stamp == generation) mutable.update { it.copy(watchId = null, watchPath = null, watchConfirmed = false, notice = "已停止监听") } }
    }
}
