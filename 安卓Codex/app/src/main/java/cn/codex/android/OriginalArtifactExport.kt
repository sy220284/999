package cn.codex.android
import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
/** 只导出本应用新建的产物目录，拒绝链接和越界；失败时由UI提示目标可能不完整。 */
object OriginalArtifactExport {
    fun write(root:File,source:File,output:OutputStream){
        val base=root.canonicalFile;val folder=source.canonicalFile
        require(folder.parentFile==base && folder.isDirectory && !Files.isSymbolicLink(source.toPath()))
        var count=0;var total=0L
        ZipOutputStream(output).use{zip->
            fun visit(directory:File){
                val files=requireNotNull(directory.listFiles());require(files.size<=10000)
                for(file in files.sortedBy{it.name}){
                    require(++count<=10000 && !Files.isSymbolicLink(file.toPath()))
                    val canonical=file.canonicalFile;require(canonical.path.startsWith(folder.path+File.separator))
                    if(file.isDirectory)visit(file)else{
                        require(file.isFile);val name=file.relativeTo(folder).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(name));file.inputStream().use{input->val buffer=ByteArray(8192);while(true){if(Thread.currentThread().isInterrupted)throw InterruptedIOException();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=64L*1024*1024){"导出内容超过64MiB"};zip.write(buffer,0,n)}};zip.closeEntry()
                    }
                }
            }
            visit(folder)
        }
    }
}
