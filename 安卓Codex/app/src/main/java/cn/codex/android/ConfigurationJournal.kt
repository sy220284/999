package cn.codex.android

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import cn.codex.core.original.OriginalMutationLifecycle
import cn.codex.core.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class PendingConfiguration(val id: String, val kind: String, val startedAt: Long)

/** 只记录操作身份/时间，不记录命令、配置内容、令牌或请求，不自动重放。 */
class ConfigurationJournal(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "原版配置操作.json"))
    private val errorMutable = MutableStateFlow<String?>(null)
    val readError = errorMutable.asStateFlow()
    private val mutable = MutableStateFlow<List<PendingConfiguration>>(read())
    val records = mutable.asStateFlow()
    private val activeMutable = MutableStateFlow<String?>(null)
    val active = activeMutable.asStateFlow()
    private fun read(): List<PendingConfiguration> {
        if (!file.baseFile.exists()) return emptyList()
        return try { Json.parseToJsonElement(file.openRead().bufferedReader().use { it.readText() }).jsonArray.map { value ->
            val item = value.jsonObject; PendingConfiguration(item.string("id"), item.string("kind"), item.getValue("startedAt").jsonPrimitive.long)
        } } catch (error: Exception) { errorMutable.value = "上次配置操作记录无法读取，请核查扩展与服务实际状态"; emptyList() }
    }
    private fun persist(entries: List<PendingConfiguration>) {
        val data = JsonArray(entries.map { buildJsonObject { put("id", it.id); put("kind", it.kind); put("startedAt", it.startedAt) } }).toString()
        val stream = file.startWrite()
        try { stream.write(data.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        mutable.value = entries
    }
    fun lifecycle(kind: String) = object : OriginalMutationLifecycle {
        override fun begin(): String {
            check(errorMutable.value == null) { "请先核查上次配置状态并确认" }
            check(activeMutable.value == null) { "另一个配置操作正在进行" }
            val entry = PendingConfiguration(UUID.randomUUID().toString(), kind, System.currentTimeMillis())
            persist(mutable.value + entry)
            activeMutable.value = entry.id
            try { context.startForegroundService(Intent(context, ConfigurationExecutionService::class.java).putExtra("ticket", entry.id).putExtra("kind", kind)) }
            catch (failure: Exception) { activeMutable.value = null; throw failure }
            return entry.id
        }
        override fun finish(ticket: String, confirmed: Boolean) {
            if (activeMutable.value != ticket) return
            try { if (confirmed) persist(mutable.value.filterNot { it.id == ticket }) }
            finally { activeMutable.value = null }
        }
    }
    fun confirmReviewed() { persist(mutable.value.filter { it.id == activeMutable.value }); errorMutable.value = null }
}
