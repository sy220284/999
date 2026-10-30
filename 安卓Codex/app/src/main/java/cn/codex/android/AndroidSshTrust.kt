package cn.codex.android
import android.content.Context
import android.util.AtomicFile
import cn.codex.core.original.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class AndroidSshTrust(context:Context,private val scope:CoroutineScope,private val busy:()->Boolean,private val lifecycle:OriginalMutationLifecycle) {
    data class State(val working:Boolean=false,val loaded:Boolean=false,val hosts:List<OriginalSshHostKey> = emptyList(),val error:String?=null,val message:String="尚未读取SSH主机记录")
    private val mutable=MutableStateFlow(State());val state=mutable.asStateFlow()
    private val root=directory(context)
    private val file=AtomicFile(File(root,"known_hosts"))
    private var job:Job?=null
    private fun read():List<OriginalSshHostKey>{
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return emptyList()
        val bytes=file.openRead().use{it.readNBytes(256*1024+1)};require(bytes.size<=256*1024)
        val rows=bytes.toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.map{val parts=it.split(' ',limit=2);require(parts.size==2);OriginalSshHostKey.parse(parts[0],parts[1])}.toList()
        require(rows.size<=200 && rows.map{it.host}.distinct().size==rows.size){"主机记录重复或过多"};return rows
    }
    private fun write(rows:List<OriginalSshHostKey>){
        require(rows.size<=200);val out=file.startWrite()
        try{out.write(rows.joinToString("\n",postfix=if(rows.isEmpty())""else"\n"){it.line}.toByteArray());file.finishWrite(out)}catch(error:Exception){file.failWrite(out);throw error}
        check(read()==rows)
    }
    private fun operation(change:Boolean,action:()->Unit){
        if(state.value.working)return
        if(change && busy()){mutable.update{it.copy(error="请先结束其他执行任务或配置变更")};return}
        val ticket=if(change)try{lifecycle.begin()}catch(_:Exception){mutable.update{it.copy(error="无法托管主机记录变更")};return}else null
        mutable.update{it.copy(working=true,error=null)}
        job=scope.launch{var confirmed=false
            try{withContext(Dispatchers.IO){synchronized(file){action()}};confirmed=true;mutable.update{it.copy(loaded=true,message="SSH主机记录已核对")}}
            catch(error:CancellationException){throw error}catch(error:Exception){mutable.update{it.copy(error=error.message ?: "主机记录失败")}}
            finally{try{if(ticket!=null)lifecycle.finish(ticket,confirmed)}finally{mutable.update{it.copy(working=false)}}}
        }
    }
    fun refresh()=operation(false){val rows=read();mutable.update{it.copy(hosts=rows)}}
    fun save(value:OriginalSshHostKey,approved:OriginalSshHostKey?)=operation(true){val rows=read();require(rows.firstOrNull{it.host==value.host}==approved){"此主机记录已经变化，请重新读取确认"};val next=rows.filterNot{it.host==value.host}+value;write(next);mutable.update{it.copy(hosts=next)}}
    fun remove(approved:OriginalSshHostKey)=operation(true){val rows=read();require(rows.firstOrNull{it.host==approved.host}==approved){"主机记录已经变化"};val next=rows.filterNot{it.host==approved.host};write(next);mutable.update{it.copy(hosts=next)}}
    companion object {
        fun directory(context:Context)=File(context.filesDir,"SSH配置").apply{mkdirs()}
        private fun quote(value:String)="'"+value.replace("'","'\"'\"'")+"'"
        fun environment(context:Context):Map<String,String>{
            val root=directory(context);val executable=File(context.applicationInfo.nativeLibraryDir,"libcodex_ssh.so")
            if(!executable.isFile || !executable.canExecute())return emptyMap()
            val command=listOf(executable.absolutePath,"-F","/dev/null","-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile=${File(root,"known_hosts").absolutePath}","-o","GlobalKnownHostsFile=/dev/null","-o","UpdateHostKeys=no","-o","ForwardAgent=no","-o","ForwardX11=no","-o","PermitLocalCommand=no","-o","IdentityAgent=none","-o","IdentitiesOnly=yes","-o","IdentityFile=${File(root,"id_ed25519").absolutePath}").joinToString(" ",transform=::quote)
            return mapOf("GIT_SSH_COMMAND" to command,"GIT_SSH_VARIANT" to "ssh")
        }
    }
}
