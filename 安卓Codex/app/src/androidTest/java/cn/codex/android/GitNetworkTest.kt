package cn.codex.android

import androidx.test.core.app.ApplicationProvider
import cn.codex.core.ToolCall
import cn.codex.core.Workspace
import cn.codex.core.obj
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 设备测试源码，未执行；不连接任何远端服务器。 */
class GitNetworkTest {
    @Test fun encryptedCredentialsAreOnlyReturnedToTheSelectedRepositoryOrigin() {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val store = GitCredentialStore(app)
        val url = "https://example.invalid/git-${System.nanoTime()}/repository.git"
        try {
            store.save(url, "test-user", "测试令牌")
            val provider = requireNotNull(store.provider(url))
            val username = CredentialItem.Username(); val password = CredentialItem.Password()
            assertTrue(provider.get(URIish(url), username, password))
            assertEquals("test-user", username.value); assertArrayEquals("测试令牌".toCharArray(), password.value)
            assertFalse(provider.get(URIish("https://other.invalid/repository.git"), CredentialItem.Password()))
            assertFalse(provider.get(URIish("http://example.invalid/repository.git"), CredentialItem.Password()))
            assertFalse(provider.get(URIish("https://example.invalid/another/repository.git"), CredentialItem.Password()))
        } finally { store.remove(url) }
        assertNull(store.provider(url))
    }
    @Test fun changedRemoteIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CodexApplication>()
        val directory = File(app.cacheDir, "git-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            Git.init().setDirectory(directory).call().use { git -> git.repository.config.apply { setString("remote", "origin", "url", "https://example.invalid/actual.git"); save() } }
            val call = ToolCall("test", "git", obj("action" to "fetch", "remote" to "origin", "expected_url" to "https://example.invalid/reviewed.git"))
            try { GitTools(Workspace(directory)).execute(call); fail("未拒绝已变化的远端") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("确认值不同")) }
        } finally { directory.deleteRecursively() }
    }
}
