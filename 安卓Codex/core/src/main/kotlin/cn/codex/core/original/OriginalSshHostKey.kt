package cn.codex.core.original
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64

data class OriginalSshHostKey(val host:String,val type:String,val encoded:String,val fingerprint:String) {
    val line get()="$host $type $encoded"
    companion object {
        private fun host(value:String):String {
            require(value.isNotBlank() && value.length<=300 && value.none{it.isWhitespace() || it in ",*?!\\\"'|;`$"}){"请提供一个明确的主机名，不能使用通配符或多个主机"}
            var name=value;var port=22
            if(value.startsWith('[')){
                val end=value.indexOf(']');require(end>1);name=value.substring(1,end)
                val tail=value.substring(end+1);require(tail.isEmpty() || tail.matches(Regex(":[0-9]{1,5}")))
                if(tail.isNotEmpty())port=tail.drop(1).toInt();require(port in 1..65535)
            }
            if(':'in name)require(name.matches(Regex("[0-9A-Fa-f:.]+")) && InetAddress.getByName(name) is Inet6Address){"IPv6主机格式不正确"}
            else require(name.split('.').all{it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))}){"主机名格式不正确"}
            name=name.lowercase();return if(port==22)name else "[$name]:$port"
        }
        fun parse(host:String,key:String):OriginalSshHostKey {
            val name=host(host.trim());require(key.length<=16384 && '\n' !in key && '\r' !in key)
            val parts=key.trim().split(Regex("\\s+"));require(parts.size>=2)
            val type=parts[0];require(type in setOf("ssh-ed25519","ecdsa-sha2-nistp256","ssh-rsa")){"只接受Ed25519、P256或RSA主机公钥"}
            val bytes=Base64.getDecoder().decode(parts[1]);require(bytes.size<=8192)
            val input=DataInputStream(ByteArrayInputStream(bytes))
            fun field():ByteArray {val size=input.readInt();require(size in 0..8192 && size<=input.available());return ByteArray(size).also{input.readFully(it)}}
            require(field().toString(Charsets.US_ASCII)==type){"公钥声明与实际类型不符"}
            when(type){
                "ssh-ed25519"->require(field().size==32)
                "ecdsa-sha2-nistp256"->{require(field().toString(Charsets.US_ASCII)=="nistp256");val point=field();require(point.size==65 && point[0]==4.toByte())}
                "ssh-rsa"->{val e=field();val n=field();require(e.isNotEmpty() && e.size<=8 && n.isNotEmpty() && n.size<=1025 && e[0]>=0 && n[0]>=0);val exponent=BigInteger(e);require(exponent>=BigInteger.valueOf(3) && exponent.testBit(0) && BigInteger(n).bitLength()>=2048)}
            }
            require(input.available()==0){"公钥包含多余内容"}
            val encoded=Base64.getEncoder().encodeToString(bytes)
            val fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            return OriginalSshHostKey(name,type,encoded,fingerprint)
        }
    }
}
