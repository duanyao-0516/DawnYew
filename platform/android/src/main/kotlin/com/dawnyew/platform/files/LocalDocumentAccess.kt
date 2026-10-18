package com.dawnyew.platform.files
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.dawnyew.core.invalid
import java.io.*

class LocalDocumentAccess(private val context:Context) {
    private val providers=mapOf(
        "com.android.externalstorage.documents" to "com.android.externalstorage",
        "com.android.providers.downloads.documents" to "com.android.providers.downloads",
        "com.android.providers.media.documents" to "com.android.providers.media.module",
        "media" to "com.android.providers.media.module",
    )
    /** Validate provider identity before opening content; EXTRA_LOCAL_ONLY is only a picker hint. */
    fun validate(uri:Uri) {
        if(uri.scheme!="content") invalid("仅支持本地 content 文件，请先保存到设备下载目录")
        val authority=uri.authority ?: invalid("文件来源无效")
        val expected=providers[authority] ?: invalid("此文件提供方未确认为本地存储，请先保存到设备下载目录")
        val provider=context.packageManager.resolveContentProvider(authority,0) ?: invalid("文件提供方不可用")
        val approved=provider.packageName==expected || (authority in setOf("media","com.android.providers.media.documents") && provider.packageName in setOf("com.android.providers.media","com.google.android.providers.media.module"))
        if(!approved || provider.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)==0) invalid("文件提供方身份不符合本地存储要求")
        if(authority=="com.android.externalstorage.documents") {
            val id=android.provider.DocumentsContract.getDocumentId(uri)
            if(!id.startsWith("primary:") && !Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}:.*").matches(id)) invalid("请选择设备内部存储或本地存储卡")
        }
    }
    fun input(uri:Uri):InputStream {validate(uri);return context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件")}
    fun output(uri:Uri):OutputStream {validate(uri);return context.contentResolver.openOutputStream(uri,"wt") ?: throw IOException("无法写入文件")}
    fun deleteCreated(uri:Uri):Boolean = runCatching {validate(uri);android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}.getOrDefault(false)
    fun copy(input:InputStream,output:OutputStream,ensureSpace:(Long)->Unit={}) {
        val buffer=ByteArray(65536)
        while(true) {if(Thread.currentThread().isInterrupted) throw InterruptedIOException("操作取消");val n=input.read(buffer);if(n<0) break;if(n==0)continue;ensureSpace(n.toLong());output.write(buffer,0,n)}
    }
}
