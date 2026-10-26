package cn.codex.android

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException

/** 大文本留在 ViewModel，旋转屏幕时不把正文塞进系统的 Bundle。 */
class FileEditorState: ViewModel() {
    val selected = mutableStateOf<String?>(null)
    val text = mutableStateOf("")
    val original = mutableStateOf("")
}
class ComposerState: ViewModel() { val draft = mutableStateOf("") }

suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) { throw error }
catch (error: Exception) { Result.failure(error) }
