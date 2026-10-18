package com.dawnyew.platform.security
import android.content.Context
import android.util.AtomicFile
import com.dawnyew.core.Sha256
import java.io.File

class AppLockStore(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"security-state"))
    var enabled:Boolean=false;private set
    var repairRequired:Boolean=false;private set
    init {
        if(!file.baseFile.exists()) {
            if(File(context.noBackupFilesDir,"datasets").exists()) {enabled=true;repairRequired=true} else persist(false)
        } else {
            val fields=runCatching {file.readFully().toString(Charsets.UTF_8).split('|')}.getOrNull()
            val body=fields?.take(2)?.joinToString("|")
            if(fields?.size!=3 || fields[0]!="1" || fields[1] !in listOf("0","1") || fields[2]!=Sha256.hex(body!!.toByteArray())) {enabled=true;repairRequired=true}
            else enabled=fields[1]=="1"
        }
    }
    fun persist(value:Boolean) {
        val body="1|"+if(value) "1" else "0"
        val bytes=(body+"|"+Sha256.hex(body.toByteArray())).toByteArray()
        val out=file.startWrite()
        try {out.write(bytes);file.finishWrite(out);enabled=value;repairRequired=false} catch(e:Exception) {file.failWrite(out);throw e}
    }
}
