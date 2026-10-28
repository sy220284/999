package cn.codex.core.original

import kotlinx.serialization.json.*

/** 字段名对应上传版本 app-server-protocol/schema/typescript；不将 Kotlin 会话协议冒充原版 RPC。 */
class OriginalCodexClient(val connection: AppServerConnection) {
    suspend fun initialize(): JsonElement {
        val result = call("initialize", buildJsonObject {
            put("clientInfo", buildJsonObject { put("name", "codex_android"); put("title", "Codex · 随行"); put("version", "0.1.0") })
            put("capabilities", buildJsonObject { put("experimentalApi", true); put("requestAttestation", false) })
        })
        connection.notify("initialized")
        return result
    }
    suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = 60_000): JsonElement =
        connection.request(method, params, timeoutMs)
    suspend fun threads(cursor: String? = null, archived: Boolean = false) = call("thread/list", buildJsonObject {
        put("limit", 30); put("archived", archived); cursor?.let { put("cursor", it) }
    })
    suspend fun start(cwd: String, model: String?) = call("thread/start", buildJsonObject {
        put("cwd", cwd); model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        // Android UID 仅隔离其他应用，不能宣称桌面项目沙箱；UI 必须先披露并取得本次同意。
        put("sandbox", "danger-full-access"); put("approvalPolicy", "untrusted")
        put("dynamicTools", OriginalAndroidTools.definitions())
    })
    suspend fun resume(threadId: String) = call("thread/resume", buildJsonObject { put("threadId", threadId) })
    suspend fun history(threadId: String, cursor: String? = null) = call("thread/items/list", buildJsonObject {
        put("threadId", threadId); put("limit", 100); put("sortDirection", "desc"); cursor?.let { put("cursor", it) }
    })
    suspend fun read(threadId: String, includeTurns: Boolean = true) = call("thread/read", buildJsonObject { put("threadId", threadId); put("includeTurns", includeTurns) })
    suspend fun fork(threadId: String) = call("thread/fork", buildJsonObject { put("threadId", threadId) })
    suspend fun archive(threadId: String, archived: Boolean) = call(if (archived) "thread/archive" else "thread/unarchive", id(threadId))
    suspend fun rename(threadId: String, title: String) = call("thread/name/set", buildJsonObject { put("threadId", threadId); put("name", title) })
    suspend fun compact(threadId: String) = call("thread/compact/start", id(threadId))
    suspend fun turn(threadId: String, text: String, effort: String?) = call("turn/start", buildJsonObject {
        put("threadId", threadId); put("input", textInput(text))
        effort?.takeIf { it.isNotBlank() }?.let { put("effort", it) }
    })
    suspend fun steer(threadId: String, turnId: String, text: String) = call("turn/steer", buildJsonObject {
        put("threadId", threadId); put("expectedTurnId", turnId); put("input", textInput(text))
    })
    suspend fun interrupt(threadId: String, turnId: String) = call("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) })
    suspend fun account() = call("account/read", buildJsonObject { put("refreshToken", false) })
    suspend fun loginApiKey(key: String) = call("account/login/start", buildJsonObject { put("type", "apiKey"); put("apiKey", key) })
    suspend fun loginDeviceCode() = call("account/login/start", buildJsonObject { put("type", "chatgptDeviceCode") }, 180_000)
    suspend fun loginBrowser() = call("account/login/start", buildJsonObject { put("type", "chatgpt") })
    suspend fun cancelLogin(loginId: String) = call("account/login/cancel", buildJsonObject { put("loginId", loginId) })
    suspend fun logout() = call("account/logout")
    suspend fun models(cursor: String? = null) = call("model/list", buildJsonObject { cursor?.let { put("cursor", it) }; put("limit", 100) })
    suspend fun rateLimits() = call("account/rateLimits/read")
    suspend fun skills(cwd: String, forceReload: Boolean = false) = call("skills/list", buildJsonObject { put("cwds", buildJsonArray { add(cwd) }); put("forceReload", forceReload) })
    suspend fun mcp(cursor: String? = null, threadId: String? = null) = call("mcpServerStatus/list", buildJsonObject {
        cursor?.let { put("cursor", it) }; threadId?.let { put("threadId", it) }; put("limit", 30); put("detail", "full")
    })
    suspend fun reloadMcp() = call("config/mcpServer/reload")
    suspend fun hooks(cwd: String, threadId: String?) = call("hooks/list", buildJsonObject {
        if (threadId == null) put("cwds", buildJsonArray { add(cwd) }) else put("threadId", threadId)
    })
    suspend fun installedPlugins(cwd: String) = call("plugin/installed", buildJsonObject { put("cwds", buildJsonArray { add(cwd) }) })
    suspend fun pluginCatalog(cwd: String, fresh: Boolean = false) = call("plugin/list", buildJsonObject {
        put("cwds", buildJsonArray { add(cwd) }); put("forceRefetch", fresh)
    })
    suspend fun pluginDetail(plugin: OriginalPlugin) = call("plugin/read", plugin.locator())
    suspend fun installPlugin(plugin: OriginalPlugin, attemptId: String) = call("plugin/install", buildJsonObject {
        plugin.locator().forEach { (key, value) -> put(key, value) }; put("installAttemptId", attemptId)
    }, 180_000)
    suspend fun uninstallPlugin(pluginId: String) = call("plugin/uninstall", buildJsonObject { put("pluginId", pluginId) }, 180_000)
    suspend fun mcpLogin(name: String, threadId: String?) = call("mcpServer/oauth/login", buildJsonObject {
        put("name", name); threadId?.let { put("threadId", it) }; put("timeoutSecs", 180)
    })
    suspend fun config(cwd: String? = null) = call("config/read", buildJsonObject { put("includeLayers", true); cwd?.let { put("cwd", it) } })
    suspend fun writeConfig(snapshot: OriginalConfigSnapshot, edit: JsonObject) = call("config/batchWrite", OriginalConfigEdits.request(snapshot, edit))
    suspend fun writeConfig(snapshot: OriginalConfigSnapshot, edits: List<JsonObject>) = call("config/batchWrite", OriginalConfigEdits.request(snapshot, edits))
    suspend fun goal(threadId: String) = call("thread/goal/get", id(threadId))
    suspend fun setGoal(threadId: String, objective: String, status: String, budget: Long?) = call("thread/goal/set", buildJsonObject {
        put("threadId", threadId); put("origin", "user"); put("objective", objective); put("status", status); put("tokenBudget", budget?.let(::JsonPrimitive) ?: JsonNull)
    })
    suspend fun clearGoal(threadId: String) = call("thread/goal/clear", buildJsonObject { put("threadId", threadId); put("origin", "user") })
    suspend fun attachments(threadId: String, cursor: String? = null) = call("thread/attachment/list", buildJsonObject { put("threadId", threadId); put("limit", 30); cursor?.let { put("cursor", it) } })
    suspend fun addAttachment(threadId: String, type: String, key: String, payload: JsonElement) = call("thread/attachment/add", buildJsonObject { put("threadId", threadId); put("attachmentType", type); put("identityKey", key); put("payload", payload) })
    suspend fun removeAttachment(threadId: String, type: String, key: String) = call("thread/attachment/remove", buildJsonObject { put("threadId", threadId); put("attachmentType", type); put("identityKey", key) })
    suspend fun sections(cursor: String? = null) = call("threadSection/list", buildJsonObject { put("limit", 30); cursor?.let { put("cursor", it) } })
    suspend fun createSection(name: String) = call("threadSection/create", buildJsonObject { put("name", name) })
    suspend fun updateSection(sectionId: String, name: String) = call("threadSection/update", buildJsonObject { put("sectionId", sectionId); put("name", name) })
    suspend fun deleteSection(sectionId: String) = call("threadSection/delete", buildJsonObject { put("sectionId", sectionId) })
    suspend fun moveSection(threadId: String, sectionId: String?, beforeThreadId: String? = null) = call("thread/section/move", buildJsonObject { put("threadId", threadId); put("sectionId", sectionId?.let(::JsonPrimitive) ?: JsonNull); beforeThreadId?.let { put("beforeThreadId", it) } })
    suspend fun turns(threadId: String, cursor: String? = null) = call("thread/turns/list", buildJsonObject { put("threadId", threadId); put("limit", 30); put("sortDirection", "desc"); put("itemsView", "full"); cursor?.let { put("cursor", it) } })
    suspend fun revert(threadId: String, beforeTurnId: String) = call("thread/revert", buildJsonObject { put("threadId", threadId); put("beforeTurnId", beforeTurnId) })
    suspend fun deleteThread(threadId: String) = call("thread/delete", id(threadId))
    suspend fun unsubscribe(threadId: String) = call("thread/unsubscribe", id(threadId))
    suspend fun mcpResource(server: String, uri: String, threadId: String?) = call("mcpServer/resource/read", buildJsonObject { put("server", server); put("uri", uri); threadId?.let { put("threadId", it) } })
    suspend fun mcpTool(threadId: String, server: String, tool: String, arguments: JsonElement) = call("mcpServer/tool/call", buildJsonObject { put("threadId", threadId); put("server", server); put("tool", tool); put("arguments", arguments) }, 180_000)
    suspend fun exec(processId: String, command: List<String>, cwd: String, tty: Boolean, timeoutMs: Long, rows: Int, cols: Int) = call("command/exec", buildJsonObject {
        put("processId", processId); put("command", JsonArray(command.map(::JsonPrimitive))); put("cwd", cwd); put("tty", tty); put("streamStdin", true); put("streamStdoutStderr", true); put("timeoutMs", timeoutMs); put("outputBytesCap", 1_048_576)
        if (tty) put("size", buildJsonObject { put("rows", rows); put("cols", cols) })
        put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
    }, timeoutMs + 10_000)
    suspend fun writeCommand(processId: String, bytes: ByteArray, closeStdin: Boolean) = call("command/exec/write", buildJsonObject { put("processId", processId); put("deltaBase64", java.util.Base64.getEncoder().encodeToString(bytes)); put("closeStdin", closeStdin) })
    suspend fun terminateCommand(processId: String) = call("command/exec/terminate", buildJsonObject { put("processId", processId) })
    suspend fun resizeCommand(processId: String, rows: Int, cols: Int) = call("command/exec/resize", buildJsonObject { put("processId", processId); put("size", buildJsonObject { put("rows", rows); put("cols", cols) }) })
    suspend fun accountUsage(threadId: String? = null) = call("account/usage/read", buildJsonObject { threadId?.let { put("threadId", it) } })
    suspend fun workspaceMessages() = call("account/workspaceMessages/read")
    suspend fun providerCapabilities() = call("modelProvider/capabilities/read", buildJsonObject {})
    suspend fun configRequirements() = call("configRequirements/read")
    suspend fun experimentalFeatures(threadId: String? = null, cursor: String? = null) = call("experimentalFeature/list", buildJsonObject { put("limit", 30); threadId?.let { put("threadId", it) }; cursor?.let { put("cursor", it) } })
    suspend fun permissionProfiles(cwd: String, cursor: String? = null) = call("permissionProfile/list", buildJsonObject { put("cwd", cwd); put("limit", 30); cursor?.let { put("cursor", it) } })
    suspend fun setExperimentalFeature(name: String, enabled: Boolean) = call("experimentalFeature/enablement/set", buildJsonObject { put("enablement", buildJsonObject { put(name, enabled) }) })
    suspend fun readFile(path: String) = call("fs/readFile", buildJsonObject { put("path", path) })
    suspend fun writeFile(path: String, dataBase64: String) = call("fs/writeFile", buildJsonObject { put("path", path); put("dataBase64", dataBase64) })
    suspend fun createDirectory(path: String, recursive: Boolean) = call("fs/createDirectory", buildJsonObject { put("path", path); put("recursive", recursive) })
    suspend fun fileMetadata(path: String) = call("fs/getMetadata", buildJsonObject { put("path", path) })
    suspend fun readDirectory(path: String) = call("fs/readDirectory", buildJsonObject { put("path", path) })
    suspend fun removeFile(path: String, recursive: Boolean) = call("fs/remove", buildJsonObject { put("path", path); put("recursive", recursive); put("force", false) })
    suspend fun copyFile(source: String, destination: String, recursive: Boolean) = call("fs/copy", buildJsonObject { put("sourcePath", source); put("destinationPath", destination); put("recursive", recursive) })
    suspend fun watchFiles(watchId: String, path: String) = call("fs/watch", buildJsonObject { put("watchId", watchId); put("path", path) })
    suspend fun unwatchFiles(watchId: String) = call("fs/unwatch", buildJsonObject { put("watchId", watchId) })
    suspend fun review(threadId: String, target: JsonObject) = call("review/start", buildJsonObject { put("threadId", threadId); put("target", target); put("delivery", "inline") })
    suspend fun loadedThreads(cursor: String? = null) = call("thread/loaded/list", buildJsonObject { put("limit", 30); cursor?.let { put("cursor", it) } })
    suspend fun updateThreadGit(threadId: String, patch: JsonObject) = call("thread/metadata/update", buildJsonObject { put("threadId", threadId); put("gitInfo", patch) })
    suspend fun attachmentOwners(type: String, key: String, cursor: String? = null) = call("thread/attachmentOwner/list", buildJsonObject { put("attachmentType", type); put("identityKey", key); put("limit", 30); cursor?.let { put("cursor", it) } })
    suspend fun setSkillEnabled(path: String, enabled: Boolean) = call("skills/config/write", buildJsonObject { put("path", path); put("enabled", enabled) })
    suspend fun setSkillRoots(roots: List<String>) = call("skills/extraRoots/set", buildJsonObject { put("extraRoots", buildJsonArray { roots.forEach { add(it) } }) })
    suspend fun pluginSkill(plugin: OriginalPlugin, name: String) = call("plugin/skill/read", OriginalSkillConfig.remoteParams(plugin, name))
    suspend fun threadShell(threadId: String, command: String, timeoutMs: Long?) = call("thread/shellCommand", buildJsonObject { put("threadId", threadId); put("command", command); timeoutMs?.let { put("timeoutMs", it) } })
    suspend fun approveGuardian(threadId: String, event: JsonObject) = call("thread/approveGuardianDeniedAction", buildJsonObject { put("threadId", threadId); put("event", event) })
    suspend fun injectHistory(threadId: String, items: JsonArray) = call("thread/inject_items", buildJsonObject { put("threadId", threadId); put("items", items) })
    suspend fun addMarketplace(source: String, ref: String?, sparse: List<String>?) = call("marketplace/add", buildJsonObject { put("source", source); ref?.let { put("refName", it) }; sparse?.let { put("sparsePaths", buildJsonArray { it.forEach { add(it) } }) } })
    suspend fun removeMarketplace(name: String) = call("marketplace/remove", buildJsonObject { put("marketplaceName", name) })
    suspend fun upgradeMarketplace(name: String?) = call("marketplace/upgrade", buildJsonObject { name?.let { put("marketplaceName", it) } })
    suspend fun reconcilePlugins(reason: String?) = call("plugin/reconcile", buildJsonObject { reason?.let { put("reason", it) } })
    suspend fun savePluginShare(path: String, id: String?, visibility: String?, targets: JsonArray?) = call("plugin/share/save", buildJsonObject { put("pluginPath", path); id?.let { put("remotePluginId", it) }; visibility?.let { put("discoverability", it) }; targets?.let { put("shareTargets", it) } })
    suspend fun updatePluginShare(id: String, visibility: String, targets: JsonArray) = call("plugin/share/updateTargets", buildJsonObject { put("remotePluginId", id); put("discoverability", visibility); put("shareTargets", targets) })
    suspend fun pluginShares() = call("plugin/share/list", buildJsonObject {})
    suspend fun checkoutPluginShare(id: String) = call("plugin/share/checkout", buildJsonObject { put("remotePluginId", id) })
    suspend fun deletePluginShare(id: String) = call("plugin/share/delete", buildJsonObject { put("remotePluginId", id) })
    suspend fun apps(cursor: String?, thread: String?, fresh: Boolean) = call("app/list", buildJsonObject { put("limit", 30); put("forceRefetch", fresh); cursor?.let { put("cursor", it) }; thread?.let { put("threadId", it) } })
    suspend fun installedApps(thread: String?, fresh: Boolean) = call("app/installed", buildJsonObject { put("forceRefresh", fresh); thread?.let { put("threadId", it) } })
    suspend fun readApps(ids: List<String>, thread: String?) = call("app/read", buildJsonObject { put("appIds", buildJsonArray { ids.forEach { add(it) } }); put("includeTools", true); thread?.let { put("threadId", it) } })
    suspend fun gatewayStatus() = call("account/gatewayOAuth/read")
    suspend fun loginGateway() = call("account/gatewayOAuth/login")
    suspend fun cancelGateway() = call("account/gatewayOAuth/cancel")
    suspend fun consumeResetCredit(key: String, credit: String?) = call("account/rateLimitResetCredit/consume", buildJsonObject { put("idempotencyKey", key); credit?.let { put("creditId", it) } })
    suspend fun nudgeCredits(type: String) = call("account/sendAddCreditsNudgeEmail", buildJsonObject { put("creditType", type) })
    suspend fun uploadFeedback(classification: String, reason: String?, thread: String?, logs: Boolean, files: List<String>, tags: JsonObject) = call("feedback/upload", feedbackParams(classification, reason, thread, logs, files, tags))
    suspend fun saveFeedback(path: String, classification: String, reason: String?, thread: String?, logs: Boolean, files: List<String>, tags: JsonObject) = call("feedback/save", JsonObject(feedbackParams(classification, reason, thread, logs, files, tags) + ("savePath" to JsonPrimitive(path))))
    private fun feedbackParams(classification: String, reason: String?, thread: String?, logs: Boolean, files: List<String>, tags: JsonObject) = buildJsonObject { put("classification", classification); reason?.let { put("reason", it) }; thread?.let { put("threadId", it) }; put("includeLogs", logs); put("extraLogFiles", buildJsonArray { files.forEach { add(it) } }); put("tags", tags) }
    suspend fun detectExternal(home: Boolean, cwds: List<String>, days: Long?, max: Long?, source: String?, migrationSource: String?) = call("externalAgentConfig/detect", buildJsonObject { put("includeHome", home); put("cwds", buildJsonArray { cwds.forEach { add(it) } }); days?.let { put("maxSessionAgeDays", it) }; max?.let { put("maxSessions", it) }; source?.let { put("source", it) }; migrationSource?.let { put("migrationSource", it) } })
    suspend fun importExternal(items: JsonArray, source: String?, provider: String?, migrationSource: String?) = call("externalAgentConfig/import", buildJsonObject { put("migrationItems", items); source?.let { put("source", it) }; provider?.let { put("providerId", it) }; migrationSource?.let { put("migrationSource", it) } })
    suspend fun recordImport(provider: String, results: JsonArray) = call("externalAgentConfig/import/recordHistory", buildJsonObject { put("providerId", provider); put("itemTypeResults", results) })
    suspend fun importHistories() = call("externalAgentConfig/import/readHistories")
    suspend fun writeConfigValue(snapshot: OriginalConfigSnapshot, key: String, value: JsonElement, strategy: String) = call("config/value/write", buildJsonObject { put("filePath", snapshot.file); put("expectedVersion", snapshot.version); put("keyPath", key); put("value", value); put("mergeStrategy", strategy) })
    suspend fun conversationSummary(id: String?, path: String?) = call("getConversationSummary", buildJsonObject { if (id != null) put("conversationId", id) else put("rolloutPath", requireNotNull(path)) })
    suspend fun remoteDiff(cwd: String) = call("gitDiffToRemote", buildJsonObject { put("cwd", cwd) })
    suspend fun authStatus(refresh: Boolean, includeToken: Boolean = false) = call("getAuthStatus", buildJsonObject { put("includeToken", includeToken); put("refreshToken", refresh) })
    suspend fun fuzzySearch(query: String, roots: List<String>, cancellationToken: String?) = call("fuzzyFileSearch", buildJsonObject { put("query", query); put("roots", buildJsonArray { roots.forEach { add(it) } }); put("cancellationToken", cancellationToken?.let(::JsonPrimitive) ?: JsonNull) })
    private fun id(threadId: String) = buildJsonObject { put("threadId", threadId) }
    companion object {
        fun textInput(text: String) = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text); put("text_elements", buildJsonArray {}) }) }
    }
}
