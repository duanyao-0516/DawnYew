package com.dawnyew.platform.files
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.dawnyew.core.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
data class DecodedImage(val bitmap:Bitmap,val fingerprint:String)
class ImageInputService(private val context:Context,private val access:LocalDocumentAccess,private val checkCancelled:()->Unit={}) {
    fun decode(uri:Uri):DecodedImage {
        val dir=File(context.noBackupFilesDir,"staging").apply {mkdirs()};val file=File(dir,"image-${UUID.randomUUID()}")
        try {
            var total=0L;val digest=Sha256()
            access.input(uri).use {source->FileOutputStream(file).use {out->val buffer=ByteArray(65536);while(true) {checkCancelled();val n=source.read(buffer);if(n<0)break;if(n==0)continue;total+=n;if(total>20L*1024*1024) invalid("截图不能超过 20 MiB");if(dir.usableSpace<128L*1024*1024+n) invalid("暂存空间不足");out.write(buffer,0,n);digest.update(buffer.copyOf(n))}}}
            val magic=ByteArray(8);val count=file.inputStream().use {it.read(magic)}
            val png=count==8 && magic.contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))
            val jpeg=count>=3 && magic[0]==0xff.toByte() && magic[1]==0xd8.toByte() && magic[2]==0xff.toByte()
            if(!png && !jpeg) invalid("仅支持 PNG 或 JPEG 截图")
            checkCancelled()
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>24_000_000 || max(bounds.outWidth,bounds.outHeight)>12000) invalid("图片尺寸无效或超过 2400 万像素 / 12000 像素长边")
            checkCancelled()
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {decoder,info,_->
                val ratio=(1920.0/max(info.size.width,info.size.height)).coerceAtMost(1.0)
                decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener {false}
            }
            try {checkCancelled();return DecodedImage(bitmap,digest.finishHex())} catch(e:Throwable) {bitmap.recycle();throw e}
        } finally {file.delete()}
    }
}
