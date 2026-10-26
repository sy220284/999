package cn.codex.core

/** SSE 数据以空行结束，可含注释和多个 data 字段。 */
class SseParser(private val event: (String) -> Unit) {
    private val data = mutableListOf<String>()
    fun line(value: String) {
        if (value.isEmpty()) {
            if (data.isNotEmpty()) { event(data.joinToString("\n")); data.clear() }
        } else if (value.startsWith("data:")) data += value.removePrefix("data:").removePrefix(" ")
    }
}
class IncompleteResponse: IllegalStateException("连接提前结束，回复未完成；可重试本轮对话")
