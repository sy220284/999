package cn.codex.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** 文件工具范围限制，不是内核级沙箱。shell 在 Android 应用 UID 权限内运行。 */
class Workspace(val root: File, val maxTextBytes: Long = 2 * 1024 * 1024) {
    init { require(root.mkdirs() || root.isDirectory) }
    fun resolve(path: String): File {
        val file = File(root, path).canonicalFile
        val base = root.canonicalFile.toPath()
        require(file.toPath().startsWith(base)) { "路径不在当前项目内：$path" }
        return file
    }
    fun read(path: String): String {
        val file = resolve(path)
        require(file.isFile) { "文件不存在：$path" }
        require(file.length() <= maxTextBytes) { "文本超过 2MB，请按行读取或使用终端处理" }
        return file.readText()
    }
    fun readLines(path: String, start: Int, limit: Int): String {
        require(start >= 1 && limit in 1..1000) { "起始行至少为 1，每次读取 1–1000 行" }
        val result = StringBuilder()
        resolve(path).bufferedReader().useLines { lines ->
            lines.drop(start - 1).take(limit).forEachIndexed { i, line ->
                require(result.length + line.length < maxTextBytes) { "选定行的文本过大" }
                result.append("${start + i}: $line\n")
            }
        }
        return result.toString()
    }
    fun write(path: String, content: String) {
        require(content.toByteArray().size <= maxTextBytes) { "单次写入超过 2MB" }
        val file = resolve(path)
        require(file != root.canonicalFile) { "不能覆盖项目目录" }
        file.parentFile!!.mkdirs()
        val temp = File.createTempFile(".codex-", ".tmp", file.parentFile)
        try {
            temp.writeText(content)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
    fun previewWrite(path: String, content: String?) = FileChange(path, resolve(path).takeIf { it.isFile }?.let { read(path) }, content)
    fun commit(change: FileChange) = commitAll(listOf(change))
    fun commitAll(changes: List<FileChange>) {
        require(changes.map { resolve(it.path).canonicalPath }.distinct().size == changes.size) { "同一批修改不能通过路径别名重复修改同一文件" }
        changes.forEach { c ->
            val f = resolve(c.path)
            require(!f.isDirectory) { "不能以文件操作覆盖目录" }
            val actual = f.takeIf { it.exists() }?.let { read(c.path) }
            require(actual == c.before) { "文件在确认前已变化，请重新生成修改：${c.path}" }
            require(c.after == null || c.after.toByteArray().size <= maxTextBytes)
        }
        val applied = mutableListOf<FileChange>()
        try {
            changes.forEach { c ->
                if (c.after == null) check(resolve(c.path).delete()) { "删除失败：${c.path}" } else write(c.path, c.after)
                applied += c
            }
        } catch (e: Exception) {
            applied.asReversed().forEach { c -> if (c.before == null) resolve(c.path).delete() else write(c.path, c.before) }
            throw e
        }
    }
    fun list(path: String = ""): List<FileEntry> = resolve(path).listFiles()?.map { f ->
        FileEntry(f.relativeTo(root).path, f.name, f.isDirectory, f.length())
    }?.sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name }) ?: emptyList()
    fun search(query: String, content: Boolean = false): String {
        require(query.isNotBlank())
        val hits = mutableListOf<String>()
        root.walkTopDown().onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "build") && !Files.isSymbolicLink(it.toPath()) && resolve(it.relativeTo(root).path) == it.canonicalFile }.forEach { f ->
            if (hits.size >= 200) return@forEach
            val path = f.relativeTo(root).path
            if (path.contains(query, ignoreCase = true)) hits += path
            if (content && f.isFile && f.length() < maxTextBytes && !Files.isSymbolicLink(f.toPath())) {
                f.useLines { lines -> lines.forEachIndexed { i, line -> if (hits.size < 200 && line.contains(query, true)) hits += "$path:${i + 1}: ${line.take(240)}" } }
            }
        }
        return hits.joinToString("\n")
    }
    fun metadata(path: String): FileEntry {
        val file = resolve(path)
        require(file.exists()) { "路径不存在：$path" }
        return FileEntry(path.ifBlank { "." }, file.name, file.isDirectory, file.length(), file.lastModified())
    }

    /**
     * 支持二进制文件和目录的原子复制。禁止目标覆盖与目录自包含。
     * 写入同级临时路径并完成所有文件后才移动到最终目标。
     */
    fun copyPath(source: String, destination: String) {
        val from = resolve(source)
        val to = resolve(destination)
        require(from.exists() && from != root.canonicalFile) { "源路径不存在或为项目根目录" }
        require(!to.exists() && to != root.canonicalFile) { "目标已存在或为项目根目录" }
        require(!to.toPath().startsWith(from.toPath())) { "目标不能位于源目录内" }
        require(!Files.isSymbolicLink(from.toPath())) { "不能复制符号链接" }
        val parent = to.parentFile
        require(parent.isDirectory) { "目标父目录不存在" }
        val temp = if (from.isDirectory) Files.createTempDirectory(parent.toPath(), ".codex-copy-").toFile()
                   else Files.createTempFile(parent.toPath(), ".codex-copy-", ".tmp").toFile()
        try {
            if (from.isDirectory) {
                Files.walkFileTree(from.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val relative = from.toPath().relativize(dir)
                        Files.createDirectories(temp.toPath().resolve(relative))
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(attrs.isRegularFile && !Files.isSymbolicLink(file)) { "源目录包含符号链接或特殊文件" }
                        val destinationFile = temp.toPath().resolve(from.toPath().relativize(file))
                        Files.copy(file, destinationFile)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else Files.copy(from.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            require(!to.exists()) { "目标在复制期间发生变化" }
            Files.move(temp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    /**
     * 删除文件或显式批准的目录。默认不递归，避免模型误删整个项目；
     * 目录删除不跟随符号链接，绝不越过工作区。
     */
    fun removePath(path: String, recursive: Boolean = false) {
        val target = resolve(path)
        require(target != root.canonicalFile && target.exists()) { "不能删除项目根目录或不存在的路径" }
        if (target.isDirectory) {
            require(recursive || target.listFiles().isNullOrEmpty()) { "非空文件夹需要显式设置 recursive=true" }
            Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else Files.delete(target.toPath())
    }
    fun previewPatch(patch: String): List<FileChange> = PatchParser(this).parse(patch)
    fun applyPatch(patch: String) = commitAll(previewPatch(patch))
}
data class FileEntry(val path: String, val name: String, val directory: Boolean, val size: Long, val modified: Long = 0)
