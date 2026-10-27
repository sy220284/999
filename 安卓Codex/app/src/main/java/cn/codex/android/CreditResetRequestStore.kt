package cn.codex.android

import android.content.Context
import android.util.AtomicFile
import cn.codex.core.original.*
import cn.codex.core.string
import kotlinx.serialization.json.*
import java.io.File

/** 仅保存幂等标识和券标识，未知结果在进程重启后仍沿用原请求。 */
class CreditResetRequestStore(context: Context) : OriginalCreditRequestStore {
    private val file = AtomicFile(File(context.filesDir, "额度重置请求.json"))
    @Synchronized override fun read(): OriginalCreditRequest? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val value = file.openRead().bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val key = value.string("key"); require(key.isNotBlank()) { "请求标识缺失" }
        return OriginalCreditRequest(key, value.string("creditId"))
    }
    @Synchronized override fun write(request: OriginalCreditRequest) {
        val stream = file.startWrite()
        try { stream.write(buildJsonObject { put("key", request.key); put("creditId", request.creditId) }.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        check(read() == request) { "重置券请求记录未能确认保存" }
    }
    @Synchronized override fun clear() { file.delete() }
}
