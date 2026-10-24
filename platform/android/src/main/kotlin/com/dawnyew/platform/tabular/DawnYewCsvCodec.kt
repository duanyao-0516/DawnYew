package com.dawnyew.platform.tabular

import com.dawnyew.core.*
import com.dawnyew.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

/** UTF-8, strict quoting and fixed framing. Caller owns streams; no platform or network side effects. */
object DawnYewCsvCodec {
    fun read(input:InputStream,mimeType:String="text/csv",checkCancelled:()->Unit={}):DawnYewCsvDocument {
        checkCancelled()
        val digest=MessageDigest.getInstance("SHA-256")
        var byteCount=0L
        val bounded=object:InputStream() {
            override fun read():Int { val value=input.read();if(value>=0) { count(1);digest.update(value.toByte()) };return value }
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int { val n=input.read(bytes,offset,length);if(n>0) { count(n);digest.update(bytes,offset,n) };return n }
            fun count(n:Int) { byteCount+=n;if(byteCount>DAWNYEW_CSV_MAX_BYTES) invalid("CSV文件超过32MiB");checkCancelled() }
        }
        val reader=InputStreamReader(bounded,Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
        val records=mutableListOf<CsvPhysicalRecord>()
        val cells=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var afterQuote=false;var fieldStarted=false;var recordStarted=false
        var line=1;var recordStart=1;var previousCr=false;var skipLf=false;var lastWasBreak=false;var first=true;var totalRecords=0;var chars=0
        fun bad(message:String):Nothing=invalid("CSV物理行$line：$message")
        fun append(char:Char) { field.append(char);if(field.length>DAWNYEW_CSV_MAX_FIELD_BYTES) bad("字段超过8KiB") }
        fun finishField() {
            val value=field.toString();if(value.toByteArray(Charsets.UTF_8).size>DAWNYEW_CSV_MAX_FIELD_BYTES) bad("字段超过8KiB")
            cells+=value;if(cells.size>8) bad("交易列数超过8列")
            field.clear();fieldStarted=false;afterQuote=false
        }
        fun finishRecord(endLine:Int) {
            if(!recordStarted && field.isEmpty() && cells.isEmpty()) bad("不允许空记录")
            finishField();totalRecords++
            when(totalRecords) {
                1 -> if(cells!=listOf("DawnYew CSV","1")) bad("需要DawnYew CSV,1格式记录")
                2 -> if(cells!=dawnYewCsvHeader) bad("CSV表头或列顺序无效")
                else -> {
                    if(records.size>=DAWNYEW_CSV_MAX_RECORDS) bad("CSV最多10000条交易，不会截断导入")
                    if(cells.size!=8) bad("CSV交易必须恰有8列")
                    records+=CsvPhysicalRecord(cells.toList(),records.size+1,recordStart,endLine)
                }
            }
            cells.clear();recordStarted=false
        }
        try {
            while(true) {
                val value=reader.read();if(value<0) break
                val c=value.toChar()
                if(++chars%4096==0) checkCancelled()
                if(first) { first=false;if(c=='\uFEFF') continue }
                val beforeLine=line
                if(c=='\r') line++ else if(c=='\n' && !previousCr) line++
                previousCr=c=='\r';lastWasBreak=c=='\r' || c=='\n'
                if(skipLf && c=='\n') { skipLf=false;recordStart=line;continue }
                skipLf=false
                if(quoted) {
                    if(c=='"') { quoted=false;afterQuote=true } else append(c)
                    continue
                }
                if(afterQuote) {
                    when(c) {
                        '"' -> { append('"');quoted=true;afterQuote=false }
                        ',' -> { finishField();recordStarted=true }
                        '\r','\n' -> { finishRecord(beforeLine);recordStart=line;skipLf=c=='\r' }
                        else -> bad("闭合引号后只能是逗号或换行")
                    }
                } else when(c) {
                    '"' -> { if(fieldStarted || field.isNotEmpty()) bad("引号只能位于字段开头");quoted=true;fieldStarted=true;recordStarted=true }
                    ',' -> { finishField();recordStarted=true }
                    '\r','\n' -> { finishRecord(beforeLine);recordStart=line;skipLf=c=='\r' }
                    else -> { append(c);fieldStarted=true;recordStarted=true }
                }
            }
            if(quoted) bad("引号字段未闭合")
            if(recordStarted || cells.isNotEmpty() || field.isNotEmpty() || afterQuote) finishRecord(line)
            if(totalRecords<2) bad("缺少格式记录或表头")
            checkCancelled()
            val physical=if(lastWasBreak) line-1 else line
            val hash=digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
            return DawnYewCsvDocument(DocumentFacts(mimeType,DocumentContainer.CSV,physical,hash,records.size),records)
        } catch(error:CharacterCodingException) { invalid("CSV不是有效的UTF-8文本") }
    }

    /** Encode before creating the SAF destination so oversized output cannot leave a partial file. */
    fun encode(prepared:PreparedCsvExport,checkCancelled:()->Unit={}):ByteArray {
        if(prepared.records.size>DAWNYEW_CSV_MAX_RECORDS || prepared.summary.includedCount!=prepared.records.size) invalid("CSV最多导出10000条交易")
        val output=ByteArrayOutputStream()
        fun row(fields:List<String>) {
            checkCancelled()
            val bytes=(fields.joinToString(",") { field ->
                codePointCount(field)
                if(field.toByteArray(Charsets.UTF_8).size>DAWNYEW_CSV_MAX_FIELD_BYTES) invalid("CSV字段超过8KiB")
                if(field.any { it==',' || it=='"' || it=='\r' || it=='\n' }) "\""+field.replace("\"","\"\"")+"\"" else field
            }+"\r\n").toByteArray(Charsets.UTF_8)
            if(output.size().toLong()+bytes.size>DAWNYEW_CSV_MAX_BYTES) invalid("CSV文件超过32MiB")
            output.write(bytes)
        }
        row(listOf("DawnYew CSV","1"));row(dawnYewCsvHeader)
        prepared.records.forEach { if(it.size!=8) invalid("CSV交易必须恰有8列");row(it) }
        return output.toByteArray()
    }
    fun write(output:OutputStream,bytes:ByteArray,checkCancelled:()->Unit={}) {
        if(bytes.size>DAWNYEW_CSV_MAX_BYTES) invalid("CSV文件超过32MiB")
        var offset=0
        while(offset<bytes.size) { checkCancelled();val size=minOf(8192,bytes.size-offset);output.write(bytes,offset,size);offset+=size }
        output.flush();checkCancelled()
    }
}
