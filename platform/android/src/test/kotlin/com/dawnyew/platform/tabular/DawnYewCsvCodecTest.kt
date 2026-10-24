package com.dawnyew.platform.tabular

import com.dawnyew.core.*
import com.dawnyew.protocol.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlin.test.*
import org.junit.Test

class DawnYewCsvCodecTest {
    private val header="DawnYew CSV,1\r\n"+dawnYewCsvHeader.joinToString(",")+"\r\n"
    private val row=listOf("00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002","EXPENSE","12.34","2026-10-01T10:00:00","'日常","'餐饮","''=SUM(1,2)\r\n\"午餐\"")
    private fun export(rows:List<List<String>>)=PreparedCsvExport(rows,CsvExportSummary(rows.size,0,0,0,0))
    @Test fun rfcQuotingMultilineBomAndApostropheRoundTripExactly() {
        val bytes=DawnYewCsvCodec.encode(export(listOf(row)))
        val parsed=DawnYewCsvCodec.read(ByteArrayInputStream(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+bytes))
        assertEquals(row,parsed.records.single().cells)
        assertEquals(1,parsed.facts.logicalRecordCount);assertEquals(4,parsed.facts.physicalRowCount)
        assertEquals(3,parsed.records.single().startLine);assertEquals(4,parsed.records.single().endLine)
        val output=ByteArrayOutputStream();DawnYewCsvCodec.write(output,bytes);assertContentEquals(bytes,output.toByteArray())
        assertEquals(Sha256.hex(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+bytes),parsed.facts.fileDigest)
    }
    @Test fun malformedQuotesBlankRowsWrongHeaderAndInvalidUtf8AreRejected() {
        val valid=row.take(7).joinToString(",")+",'note\n"
        listOf(header+valid+"\n",header+"bad\"quote\n",header+"\"unfinished",header.replace("DawnYew CSV,1","DawnYew CSV,2")+valid,header+"a,b\n").forEach { text -> assertFailsWith<DomainException> { DawnYewCsvCodec.read(ByteArrayInputStream(text.toByteArray())) } }
        assertFailsWith<DomainException> { DawnYewCsvCodec.read(ByteArrayInputStream(header.toByteArray()+byteArrayOf(0xc3.toByte()))) }
    }
    @Test fun fieldLimitCountsUtf8BytesAndFileLimitIsEnforcedDuringRead() {
        val tooLarge=row.take(7).joinToString(",")+",'"+"中".repeat(3000)+"\n"
        assertFailsWith<DomainException> { DawnYewCsvCodec.read(ByteArrayInputStream((header+tooLarge).toByteArray())) }
        val largeRow=(List(8) { "a".repeat(8192) }.joinToString(",")+"\n").toByteArray()
        val streams=listOf(ByteArrayInputStream(header.toByteArray()))+List(600) { ByteArrayInputStream(largeRow) }
        val error=assertFailsWith<DomainException> { DawnYewCsvCodec.read(SequenceInputStream(Collections.enumeration(streams))) }
        assertTrue(error.message!!.contains("32MiB"),error.message)
    }
    @Test fun tenThousandRecordBoundaryNeverTruncatesAndCancellationPropagates() {
        val plain=row.dropLast(1)+"'note"
        val accepted=DawnYewCsvCodec.read(ByteArrayInputStream(DawnYewCsvCodec.encode(export(List(10000) { plain }))))
        assertEquals(10000,accepted.records.size)
        assertFailsWith<DomainException> { DawnYewCsvCodec.encode(export(List(10001) { plain })) }
        val bytes=(header+List(10001) { plain.joinToString(",") }.joinToString("\n")).toByteArray()
        assertFailsWith<DomainException> { DawnYewCsvCodec.read(ByteArrayInputStream(bytes)) }
        assertFailsWith<IllegalStateException> { DawnYewCsvCodec.read(ByteArrayInputStream(header.toByteArray())) { error("cancelled") } }
    }
}
