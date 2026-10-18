package com.dawnyew.platform.tabular

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OoxmlCodecTest {
    private val directory = Files.createTempDirectory("dawnyew-xlsx-test").toFile()
    private val codec = OoxmlCodec(directory)
    @After fun cleanup() { directory.deleteRecursively() }
    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val relns = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private fun parts(sheet: String = """<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>金额</t></is></c><c r="B1"><v>3.25E+1</v></c></row></sheetData></worksheet>""") = linkedMapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="$relns"><Relationship Id="rId1" Type="${office}officeDocument" Target="xl/workbook.xml"/></Relationships>""",
        "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="${office.dropLast(1)}"><workbookPr date1904="1"/><sheets><sheet name="原始账单" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$relns"><Relationship Id="rId1" Type="${office}worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to sheet,
    )
    private fun zip(parts: Map<String,String>, level: Int = 1): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> zip.setLevel(level); for ((name, text) in parts) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun reject(parts: Map<String,String>) {
        try { codec.read(zip(parts).inputStream()); fail("Unsafe workbook accepted") } catch (_: java.io.IOException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun readsActualOpcWorkbookPreservingRawNumbersAndDateSystem() {
        val workbook = codec.read(zip(parts()).inputStream())
        assertTrue(workbook.date1904)
        assertEquals("金额", workbook.sheets.single().rows.single()[0].value)
        assertEquals(CellKind.NUMBER, workbook.sheets.single().rows.single()[1].kind)
        assertEquals("3.25E+1", workbook.sheets.single().rows.single()[1].value)
    }
    @Test fun writesRealXlsxWithExplicitTextAndPreservesNumbersAndStyles() {
        val workbook = TableWorkbook(listOf(TableSheet("账单", listOf(listOf(
            Cell(CellKind.TEXT, "=SUM(A1:A2) & <备注>"), Cell(CellKind.NUMBER, "32.50", 1),
            Cell(CellKind.ISO_DATE, "2026-10-18T12:30:00"), Cell(CellKind.BOOLEAN, "1"), Cell(CellKind.BLANK),
        )))), date1904 = true, numberFormats = mapOf(164 to "0.00"), styleNumberFormatIds = listOf(0, 164))
        val out = ByteArrayOutputStream(); codec.write(workbook, out)
        assertEquals('P'.code.toByte(), out.toByteArray()[0]); assertEquals('K'.code.toByte(), out.toByteArray()[1])
        assertEquals(workbook, codec.read(out.toByteArray().inputStream()))
    }
    @Test fun rejectsFormulaEvenWhenCachedNumericValueExists() {
        reject(parts("""<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>"""))
    }
    @Test fun rejectsDtdAndEntityExpansion() {
        reject(parts("""<!DOCTYPE worksheet [<!ENTITY x "secret">]><worksheet xmlns="$ns"><sheetData><row><c t="inlineStr"><is><t>&x;</t></is></c></row></sheetData></worksheet>"""))
    }
    @Test fun rejectsExternalRelationshipsMacrosAndTraversal() {
        reject(parts().also { it["xl/_rels/workbook.xml.rels"] = """<Relationships xmlns="$relns"><Relationship Id="rId1" Type="${office}worksheet" Target="https://example.com/sheet.xml" TargetMode="External"/></Relationships>""" })
        reject(parts().also { it["[Content_Types].xml"] = it.getValue("[Content_Types].xml").replace("spreadsheetml.sheet.main+xml", "ms-excel.sheet.macroEnabled.main+xml") })
        for (name in listOf("../escape.xml", "/root.xml", "xl/../escape.xml", "xl\\escape.xml", "xl//escape.xml")) reject(parts().also { it[name] = "<x/>" })
        reject(parts().also { it["xl/_rels/workbook.xml.rels"] = it.getValue("xl/_rels/workbook.xml.rels").replace("worksheets/sheet1.xml", "%2e%2e/%2e%2e/escape.xml") })
    }
    @Test fun rejectsDuplicateCellsRowsAndOversizedCoordinates() {
        for (body in listOf("<row r='1'><c r='A1'/><c r='A1'/></row>", "<row r='1'/><row r='1'/>", "<row r='1048576'/>", "<row r='1'><c r='XFD1'/></row>"))
            reject(parts("""<worksheet xmlns="$ns"><sheetData>$body</sheetData></worksheet>"""))
    }
    @Test fun rejectsDuplicateZipMembersAndTrailingBytes() {
        val archive = zip(parts().also { it["dupA.xml"] = "<x/>"; it["dupB.xml"] = "<x/>" })
        val duplicate = archive.clone()
        val from = "dupB.xml".toByteArray(); val to = "dupA.xml".toByteArray()
        for (i in 0..duplicate.size-from.size) if (duplicate.copyOfRange(i, i+from.size).contentEquals(from)) to.copyInto(duplicate,i)
        for (bytes in listOf(duplicate, zip(parts()) + byteArrayOf(1))) {
            try { codec.read(bytes.inputStream()); fail("Unsafe ZIP accepted") } catch (_: java.io.IOException) { }
        }
    }
    @Test fun readsSharedStringsAndSparseCellsWithoutGuessingDates() {
        val p = parts("""<worksheet xmlns="$ns"><sheetData><row r="1"><c r="B1" t="s"><v>0</v></c><c r="C1" s="1"><v>45200</v></c></row></sheetData></worksheet>""")
        p["xl/sharedStrings.xml"] = """<sst xmlns="$ns"><si><r><t>原始</t></r><r><t>文本</t></r></si></sst>"""
        p["xl/styles.xml"] = """<styleSheet xmlns="$ns"><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>"""
        p["xl/_rels/workbook.xml.rels"] = p.getValue("xl/_rels/workbook.xml.rels").replace("</Relationships>", """<Relationship Id="rId2" Type="${office}sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId3" Type="${office}styles" Target="styles.xml"/></Relationships>""")
        p["[Content_Types].xml"] = p.getValue("[Content_Types].xml").replace("</Types>", """<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        val book = codec.read(zip(p).inputStream())
        assertEquals(listOf(0,14),book.styleNumberFormatIds)
        assertEquals(CellKind.BLANK, book.sheets.single().rows.single()[0].kind)
        assertEquals("原始文本",book.sheets.single().rows.single()[1].value)
        assertEquals(Cell(CellKind.NUMBER,"45200",1),book.sheets.single().rows.single()[2])
    }
    @Test fun rejectsOversizedTextAndExcessiveCompressionRatio() {
        reject(parts("""<worksheet xmlns="$ns"><sheetData><row><c t="inlineStr"><is><t>${"x".repeat(32769)}</t></is></c></row></sheetData></worksheet>"""))
        val bomb = zip(parts().also { it["unused.xml"]="<x>"+"a".repeat(2*1024*1024)+"</x>" },9)
        try { codec.read(bomb.inputStream()); fail("Compression bomb accepted") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("压缩比")) }
    }
    @Test fun rejectsMisplacedRowsAndForeignNamespaceCells() {
        reject(parts("""<worksheet xmlns="$ns"><sheetData/><ignored><row r="1"><c r="A1"><v>1</v></c></row></ignored></worksheet>"""))
        reject(parts("""<worksheet xmlns="$ns"><sheetData><row><c><v xmlns="urn:foreign">1</v></c></row></sheetData></worksheet>"""))
    }
    @Test fun cancellationAndSourceReadFailureRemovePrivateTemporaryFiles() {
        var calls = 0
        val cancelled = OoxmlCodec(directory, checkCancelled = { if (++calls >= 3) throw java.io.InterruptedIOException("cancelled") })
        try { cancelled.read(zip(parts()).inputStream()); fail("Cancellation ignored") } catch (_: java.io.InterruptedIOException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
        val failure = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("read failed") }
        try { codec.read(failure); fail("Read failure ignored") } catch (_: java.io.IOException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun rejectsDeclaredExternalDtdBeforeAnyEntityResolution() {
        val p = parts("""<!DOCTYPE worksheet SYSTEM "file:///definitely-does-not-exist"><worksheet xmlns="$ns"><sheetData/></worksheet>""")
        try { codec.read(zip(p).inputStream()); fail("DTD accepted") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("DTD")) }
    }

}
