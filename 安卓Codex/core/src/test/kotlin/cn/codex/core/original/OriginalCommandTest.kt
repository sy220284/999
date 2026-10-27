package cn.codex.core.original

import org.junit.Test
import kotlin.test.*

class OriginalCommandTest {
    @Test fun splitChineseAndEmojiAreDecodedWithoutReplacementCharacters() {
        val stream = CommandUtf8Stream()
        val bytes = "中文🙂".toByteArray(Charsets.UTF_8)
        val text = bytes.indices.joinToString("") { stream.append(byteArrayOf(bytes[it])) } + stream.append(byteArrayOf(), true)
        assertEquals("中文🙂", text)
    }
    @Test fun incompleteFinalUtf8IsOnlyReplacedWhenTheStreamEnds() {
        val stream = CommandUtf8Stream()
        assertEquals("", stream.append(byteArrayOf(0xe4.toByte(), 0xb8.toByte())))
        assertEquals("\uFFFD", stream.append(byteArrayOf(), true))
    }
}
