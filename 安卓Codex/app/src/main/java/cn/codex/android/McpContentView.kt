package cn.codex.android

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.codex.core.string
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 文本直接展示；图片使用缩略解码，二进制资料由用户选择位置导出。 */
@Composable fun McpContentView(content: JsonObject) {
    val raw = content["resource"] as? JsonObject ?: content
    val text = raw.string("text")
    val encoded = raw.string("data", raw.string("blob"))
    val mime = raw.string("mimeType", "application/octet-stream")
    val bytes = remember(encoded) { if (encoded.isBlank()) null else runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null && bytes != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) } } }.onFailure { error = "导出失败：${it.message.orEmpty()}" }
        }
    }
    if (text.isNotBlank()) Text(text)
    if (bytes != null && mime.startsWith("image/")) {
        val bitmap = remember(bytes) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
        if (bitmap != null) Image(bitmap, contentDescription = "工具返回图片", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
        else Text("图片暂不能预览，可以导出查看。")
    } else if (bytes != null && mime.startsWith("text/")) Text(remember(bytes) { bytes.toString(Charsets.UTF_8) })
    else if (bytes != null) Text("二进制资料 · ${bytes.size} 字节 · $mime")
    if (bytes != null) TextButton(onClick = { export.launch(raw.string("name", "工具资料")) }) { Text("保存资料") }
    if (encoded.isNotBlank() && bytes == null) Text("资料编码无效", color = MaterialTheme.colorScheme.error)
    if (raw.string("uri").isNotBlank()) Text(raw.string("uri"), style = MaterialTheme.typography.bodySmall)
    if (text.isBlank() && encoded.isBlank() && raw.string("uri").isBlank()) Text(raw.toString())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
