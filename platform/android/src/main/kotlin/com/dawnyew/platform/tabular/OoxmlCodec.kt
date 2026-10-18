package com.dawnyew.platform.tabular

import java.io.*
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.text.Normalizer
import java.util.Locale
import java.util.zip.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer

enum class CellKind { TEXT, NUMBER, BOOLEAN, ISO_DATE, BLANK }
/** A raw cell. Numeric literals and ISO date strings are never converted to money or local dates here. */
data class Cell(val kind: CellKind, val value: String = "", val styleIndex: Int? = null)
data class TableSheet(val name: String, val rows: List<List<Cell>>)
data class TableWorkbook(
    val sheets: List<TableSheet>, val date1904: Boolean = false,
    val numberFormats: Map<Int, String> = emptyMap(), val styleNumberFormatIds: List<Int> = listOf(0),
)
data class SheetInfo(val index: Int, val name: String)
data class WorkbookMetadata(
    val sheets: List<SheetInfo>, val date1904: Boolean,
    val numberFormats: Map<Int, String>, val styleNumberFormatIds: List<Int>,
)

/**
 * Restricted data-only, UTF-8 OOXML codec, not a payment-provider template adapter.
 * Use readRows for bounded row memory. Callbacks must target isolated preview/staging:
 * later semantic errors can still reject the workbook. Caller owns input/output streams.
 */
class OoxmlCodec(
    private val directory: File,
    private val checkCancelled: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("表格操作已取消")
    },
    private val ensureSpace: (Long) -> Unit = { next ->
        if (directory.usableSpace < 128L * 1024 * 1024 + next) throw IOException("表格暂存空间不足")
    },
) {
    fun read(input: InputStream): TableWorkbook {
        val rows = linkedMapOf<SheetInfo, MutableList<List<Cell>>>()
        val metadata = readRows(input) { info, _, row -> rows.getOrPut(info) { mutableListOf() }.add(row) }
        return TableWorkbook(metadata.sheets.map { TableSheet(it.name, rows[it].orEmpty()) }, metadata.date1904,
            metadata.numberFormats, metadata.styleNumberFormatIds)
    }

    fun readRows(input: InputStream, onRow: (SheetInfo, Int, List<Cell>) -> Unit): WorkbookMetadata = protect {
        withTemporary("xlsx-input-") { file ->
            file.outputStream().use { copyBounded(input, it, MAX_INPUT, true) }
            validateArchive(file)
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                // Inspect every XML part, including parts not referenced by the workbook.
                for (entry in zip.entries().asSequence()) if (!entry.isDirectory) xml(zip, entry.name) { x -> while (x.next() != XmlPullParser.END_DOCUMENT) Unit }
                val types = contentTypes(zip)
                val relations = relationships(zip, names)
                val root = relations[""] ?: bad("缺少OPC根关系")
                val bookRel = root.filter { it.type == OFFICE + "officeDocument" }.singleOrNull() ?: bad("工作簿关系不唯一")
                for (r in root) if (r.type !in ROOT_REL_TYPES) bad("不支持的根关系")
                requireType(types, bookRel.target, MAIN_TYPE)
                val bookRelations = relations[bookRel.target].orEmpty()
                for (r in bookRelations) if (r.type !in BOOK_REL_TYPES) bad("不支持的工作簿关系")
                for ((owner, rs) in relations) if (owner.isNotEmpty() && owner != bookRel.target && rs.isNotEmpty()) bad("不支持工作表附属对象")
                val book = workbook(zip, bookRel.target, bookRelations)
                val styleRel = unique(bookRelations, OFFICE + "styles")
                val styles = if (styleRel == null) Styles(emptyMap(), listOf(0)) else {
                    requireType(types, styleRel.target, STYLES_TYPE); styles(zip, styleRel.target)
                }
                val shared = unique(bookRelations, OFFICE + "sharedStrings")
                withTemporary("xlsx-strings-") { stringsFile ->
                    SharedStrings(stringsFile).use { strings ->
                        if (shared != null) { requireType(types, shared.target, STRINGS_TYPE); readSharedStrings(zip, shared.target, strings) }
                        var totalRows = 0
                        var decodedBytes = 0L
                        for ((index, sheet) in book.sheets.withIndex()) {
                            requireType(types, sheet.path, SHEET_TYPE)
                            worksheet(zip, sheet.path, strings, styles.ids.size) { rowNumber, row ->
                                if (++totalRows > MAX_ROWS) bad("表格行数超过10000")
                                for (cell in row) decodedBytes += cell.value.toByteArray(Charsets.UTF_8).size
                                if (decodedBytes > MAX_EXPANDED) bad("解码单元格文本总量超过100MiB")
                                onRow(SheetInfo(index, sheet.name), rowNumber, row)
                            }
                        }
                        WorkbookMetadata(book.sheets.mapIndexed { index, sheet -> SheetInfo(index, sheet.name) }, book.date1904, styles.formats, styles.ids)
                    }
                }
            }
        }
    }

    fun write(workbook: TableWorkbook, output: OutputStream): Unit = protect {
        validateWrite(workbook)
        withTemporary("xlsx-output-") { file ->
            file.outputStream().use { raw ->
                val limited = object : FilterOutputStream(raw) {
                    var count = 0L
                    override fun write(value: Int) { checkLimit(1); out.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { checkLimit(length); out.write(bytes, offset, length) }
                    fun checkLimit(n: Int) { count += n; if (count > MAX_INPUT) bad("表格原文件超过20MiB"); ensureSpace(n.toLong()) }
                }
                ZipOutputStream(limited).use { zip ->
                    zip.setLevel(1) // Avoid producing highly repetitive files above the accepted ratio.
                    writeXml(zip, "[Content_Types].xml", CONTENT_NS, "Types") { s ->
                        empty(s, CONTENT_NS, "Default", "Extension" to "rels", "ContentType" to REL_TYPE)
                        empty(s, CONTENT_NS, "Default", "Extension" to "xml", "ContentType" to "application/xml")
                        empty(s, CONTENT_NS, "Override", "PartName" to "/xl/workbook.xml", "ContentType" to MAIN_TYPE)
                        empty(s, CONTENT_NS, "Override", "PartName" to "/xl/styles.xml", "ContentType" to STYLES_TYPE)
                        for (i in workbook.sheets.indices) empty(s, CONTENT_NS, "Override", "PartName" to "/xl/worksheets/sheet${i + 1}.xml", "ContentType" to SHEET_TYPE)
                    }
                    writeXml(zip, "_rels/.rels", REL_NS, "Relationships") { s ->
                        empty(s, REL_NS, "Relationship", "Id" to "rId1", "Type" to OFFICE + "officeDocument", "Target" to "xl/workbook.xml")
                    }
                    writeXml(zip, "xl/workbook.xml", MAIN_NS, "workbook") { s ->
                        s.setPrefix("r", OFFICE.dropLast(1))
                        empty(s, MAIN_NS, "workbookPr", "date1904" to if (workbook.date1904) "1" else "0")
                        s.startTag(MAIN_NS, "sheets")
                        for ((i, sheet) in workbook.sheets.withIndex()) {
                            s.startTag(MAIN_NS, "sheet").attribute("", "name", sheet.name).attribute("", "sheetId", (i + 1).toString())
                                .attribute(OFFICE.dropLast(1), "id", "rId${i + 1}").endTag(MAIN_NS, "sheet")
                        }
                        s.endTag(MAIN_NS, "sheets")
                    }
                    writeXml(zip, "xl/_rels/workbook.xml.rels", REL_NS, "Relationships") { s ->
                        for (i in workbook.sheets.indices) empty(s, REL_NS, "Relationship", "Id" to "rId${i + 1}", "Type" to OFFICE + "worksheet", "Target" to "worksheets/sheet${i + 1}.xml")
                        empty(s, REL_NS, "Relationship", "Id" to "rId${workbook.sheets.size + 1}", "Type" to OFFICE + "styles", "Target" to "styles.xml")
                    }
                    writeXml(zip, "xl/styles.xml", MAIN_NS, "styleSheet") { s ->
                        s.startTag(MAIN_NS, "numFmts").attribute("", "count", workbook.numberFormats.size.toString())
                        for ((id, format) in workbook.numberFormats.toSortedMap()) empty(s, MAIN_NS, "numFmt", "numFmtId" to id.toString(), "formatCode" to format)
                        s.endTag(MAIN_NS, "numFmts")
                        s.startTag(MAIN_NS, "fonts").attribute("", "count", "1"); s.startTag(MAIN_NS, "font"); empty(s, MAIN_NS, "sz", "val" to "11"); empty(s, MAIN_NS, "name", "val" to "Calibri"); s.endTag(MAIN_NS, "font").endTag(MAIN_NS, "fonts")
                        s.startTag(MAIN_NS, "fills").attribute("", "count", "2")
                        for (pattern in listOf("none", "gray125")) { s.startTag(MAIN_NS, "fill"); empty(s, MAIN_NS, "patternFill", "patternType" to pattern); s.endTag(MAIN_NS, "fill") }
                        s.endTag(MAIN_NS, "fills")
                        s.startTag(MAIN_NS, "borders").attribute("", "count", "1"); s.startTag(MAIN_NS, "border"); for (edge in listOf("left", "right", "top", "bottom", "diagonal")) empty(s, MAIN_NS, edge); s.endTag(MAIN_NS, "border").endTag(MAIN_NS, "borders")
                        s.startTag(MAIN_NS, "cellStyleXfs").attribute("", "count", "1"); empty(s, MAIN_NS, "xf", "numFmtId" to "0", "fontId" to "0", "fillId" to "0", "borderId" to "0"); s.endTag(MAIN_NS, "cellStyleXfs")
                        s.startTag(MAIN_NS, "cellXfs").attribute("", "count", workbook.styleNumberFormatIds.size.toString())
                        for (id in workbook.styleNumberFormatIds) empty(s, MAIN_NS, "xf", "numFmtId" to id.toString(), "fontId" to "0", "fillId" to "0", "borderId" to "0", "xfId" to "0")
                        s.endTag(MAIN_NS, "cellXfs")
                        s.startTag(MAIN_NS, "cellStyles").attribute("", "count", "1"); empty(s, MAIN_NS, "cellStyle", "name" to "Normal", "xfId" to "0", "builtinId" to "0"); s.endTag(MAIN_NS, "cellStyles")
                    }
                    for ((i, sheet) in workbook.sheets.withIndex()) writeXml(zip, "xl/worksheets/sheet${i + 1}.xml", MAIN_NS, "worksheet") { s ->
                        s.startTag(MAIN_NS, "sheetData")
                        for ((rowIndex, row) in sheet.rows.withIndex()) {
                            checkCancelled(); s.startTag(MAIN_NS, "row").attribute("", "r", (rowIndex + 1).toString())
                            for ((column, cell) in row.withIndex()) {
                                s.startTag(MAIN_NS, "c").attribute("", "r", columnName(column) + (rowIndex + 1))
                                cell.styleIndex?.let { s.attribute("", "s", it.toString()) }
                                when (cell.kind) {
                                    CellKind.TEXT -> { s.attribute("", "t", "inlineStr"); s.startTag(MAIN_NS, "is").startTag(MAIN_NS, "t").attribute(XML_NS, "space", "preserve").text(cell.value).endTag(MAIN_NS, "t").endTag(MAIN_NS, "is") }
                                    CellKind.BLANK -> Unit
                                    else -> { if (cell.kind == CellKind.BOOLEAN) s.attribute("", "t", "b") else if (cell.kind == CellKind.ISO_DATE) s.attribute("", "t", "d"); s.startTag(MAIN_NS, "v").text(cell.value).endTag(MAIN_NS, "v") }
                                }
                                s.endTag(MAIN_NS, "c")
                            }
                            s.endTag(MAIN_NS, "row")
                        }
                        s.endTag(MAIN_NS, "sheetData")
                    }
                }
            }
            // Only publish a complete archive that passes the same import limits.
            file.inputStream().use { readRows(it) { _, _, _ -> Unit } }
            file.inputStream().use { copyBounded(it, output, MAX_INPUT, false) }
        }
    }

    private data class Relation(val id: String, val type: String, val target: String)
    private data class Sheet(val name: String, val path: String)
    private data class Book(val sheets: List<Sheet>, val date1904: Boolean)
    private data class Styles(val formats: Map<Int, String>, val ids: List<Int>)
    private data class Types(val defaults: Map<String, String>, val overrides: Map<String, String>)
    private fun contentTypes(zip: ZipFile): Types {
        val defaults = mutableMapOf<String, String>(); val overrides = mutableMapOf<String, String>()
        xml(zip, "[Content_Types].xml") { x ->
            x.root(CONTENT_NS, "Types")
            while (x.next() != XmlPullParser.END_DOCUMENT) if (x.event == XmlPullParser.START_TAG) {
                if (x.depth != 2 || x.namespace != CONTENT_NS) bad("无效Content Types")
                val type = x.required("ContentType")
                if (type !in ALLOWED_TYPES) bad("不支持宏、嵌入对象或非数据工作簿类型")
                when (x.name) {
                    "Default" -> { val ext = x.required("Extension"); if (ext !in setOf("xml", "rels") || defaults.put(ext, type) != null) bad("重复或无效默认类型") }
                    "Override" -> { val part = x.required("PartName"); if (!part.startsWith("/")) bad("无效PartName"); val name = validName(part.drop(1)); if (overrides.put(name, type) != null) bad("重复类型声明") }
                    else -> bad("无效Content Types成员")
                }
            }
        }
        return Types(defaults, overrides)
    }
    private fun requireType(types: Types, path: String, type: String) {
        if ((types.overrides[path] ?: types.defaults[path.substringAfterLast('.', "")]) != type) bad("部件类型与关系不匹配")
    }
    private fun relationships(zip: ZipFile, names: Set<String>): Map<String, List<Relation>> {
        val result = mutableMapOf<String, List<Relation>>()
        for (name in names.filter { it.endsWith(".rels") }) {
            val owner = if (name == "_rels/.rels") "" else {
                val marker = name.lastIndexOf("/_rels/")
                if (marker < 0) bad("无效关系部件路径")
                name.substring(0, marker + 1) + name.substring(marker + 7).removeSuffix(".rels")
            }
            if (owner.isNotEmpty() && owner !in names) bad("关系来源不存在")
            val rows = mutableListOf<Relation>(); val ids = hashSetOf<String>()
            xml(zip, name) { x ->
                x.root(REL_NS, "Relationships")
                while (x.next() != XmlPullParser.END_DOCUMENT) if (x.event == XmlPullParser.START_TAG) {
                    if (x.depth != 2 || x.namespace != REL_NS || x.name != "Relationship") bad("无效关系")
                    if (x.attribute("TargetMode") !in listOf(null, "Internal")) bad("禁止外部关系")
                    val id = x.required("Id"); if (!ids.add(id)) bad("重复关系ID")
                    val target = resolve(owner, x.required("Target"))
                    if (target !in names || target.endsWith("/")) bad("关系目标不存在")
                    rows += Relation(id, x.required("Type"), target)
                }
            }
            result[owner] = rows
        }
        return result
    }
    private fun unique(relations: List<Relation>, type: String): Relation? {
        val found = relations.filter { it.type == type }
        if (found.size > 1) bad("重复单例关系")
        return found.singleOrNull()
    }
    private fun workbook(zip: ZipFile, path: String, relations: List<Relation>): Book {
        var date1904 = false; var seenDate = false
        val sheets = mutableListOf<Sheet>(); val names = hashSetOf<String>(); val ids = hashSetOf<String>(); val used = hashSetOf<String>()
        xml(zip, path) { x ->
            x.root(MAIN_NS, "workbook")
            while (x.next() != XmlPullParser.END_DOCUMENT) if (x.event == XmlPullParser.START_TAG && x.namespace == MAIN_NS) when (x.name) {
                "workbookPr" -> { if (seenDate) bad("重复workbookPr"); seenDate = true; date1904 = when (x.attribute("date1904")) { null, "0", "false" -> false; "1", "true" -> true; else -> bad("无效日期系统") } }
                "sheet" -> {
                    val name = x.required("name"); validSheetName(name)
                    if (!names.add(name.lowercase(Locale.ROOT)) || !ids.add(x.required("sheetId"))) bad("重复工作表")
                    val id = x.parser.getAttributeValue(OFFICE.dropLast(1), "id") ?: bad("工作表缺少关系ID")
                    if (!used.add(id)) bad("重复工作表关系")
                    val relation = relations.singleOrNull { it.id == id && it.type == OFFICE + "worksheet" } ?: bad("无效工作表关系")
                    sheets += Sheet(name, relation.target)
                }
                "definedNames", "externalReferences" -> bad("不支持命名公式或外部引用")
            }
        }
        if (sheets.isEmpty() || sheets.size > MAX_ENTRIES || used != relations.filter { it.type == OFFICE + "worksheet" }.map { it.id }.toSet()) bad("工作表集合不完整")
        return Book(sheets, date1904)
    }
    private fun styles(zip: ZipFile, path: String): Styles {
        val formats = mutableMapOf<Int, String>(); val ids = mutableListOf<Int>(); var inCellXfs = false
        xml(zip, path) { x ->
            x.root(MAIN_NS, "styleSheet")
            while (x.next() != XmlPullParser.END_DOCUMENT) {
                if (x.event == XmlPullParser.START_TAG && x.namespace == MAIN_NS) when (x.name) {
                    "numFmt" -> { val id = unsignedInt(x.required("numFmtId")); val format = x.required("formatCode"); if (id < 164 || formats.put(id, format) != null) bad("重复或无效自定义数字格式") }
                    "cellXfs" -> { if (inCellXfs || ids.isNotEmpty()) bad("重复单元格样式集合"); inCellXfs = true }
                    "xf" -> if (inCellXfs) { if (ids.size >= 65536) bad("样式过多"); ids += unsignedInt(x.attribute("numFmtId") ?: "0") }
                }
                if (x.event == XmlPullParser.END_TAG && x.name == "cellXfs") inCellXfs = false
            }
        }
        if (ids.isEmpty() || ids.any { it >= 164 && it !in formats }) bad("未知数字格式")
        return Styles(formats, ids)
    }

    private inner class SharedStrings(file: File) : Closeable {
        private val data = RandomAccessFile(file, "rw")
        private var offsets = LongArray(256)
        var size = 0; private set
        fun append(text: String) {
            validText(text)
            if (size >= MAX_ROWS * MAX_COLUMNS) bad("sharedStrings数量超过限额")
            if (size == offsets.size) offsets = offsets.copyOf(minOf(offsets.size * 2, MAX_ROWS * MAX_COLUMNS))
            val bytes = text.toByteArray(Charsets.UTF_8); ensureSpace(bytes.size.toLong() + 4)
            offsets[size++] = data.length(); data.seek(data.length()); data.writeInt(bytes.size); data.write(bytes)
        }
        fun get(index: Int): String {
            if (index !in 0 until size) bad("共享字符串索引越界")
            data.seek(offsets[index]); val length = data.readInt()
            if (length !in 0..MAX_TEXT_BYTES) bad("共享字符串缓存损坏")
            val bytes = ByteArray(length); data.readFully(bytes); return String(bytes, Charsets.UTF_8)
        }
        override fun close() { data.close() }
    }
    private fun readSharedStrings(zip: ZipFile, path: String, strings: SharedStrings) {
        xml(zip, path) { x ->
            x.root(MAIN_NS, "sst")
            while (x.next() != XmlPullParser.END_DOCUMENT) if (x.event == XmlPullParser.START_TAG && x.name == "si" && x.namespace == MAIN_NS) strings.append(richText(x, "si"))
        }
    }
    private fun richText(x: BoundedXml, root: String): String {
        val depth = x.depth; val out = StringBuilder(); var tDepth = -1; var phonetic = false
        while (true) {
            val event = x.next()
            if (event == XmlPullParser.END_DOCUMENT) bad("字符串部件截断")
            if (event == XmlPullParser.END_TAG && x.depth == depth && x.name == root) break
            if (event == XmlPullParser.START_TAG) {
                if (x.name == "rPh") phonetic = true
                if (x.name == "t" && !phonetic) tDepth = x.depth
            } else if (event == XmlPullParser.END_TAG) {
                if (x.name == "t") tDepth = -1
                if (x.name == "rPh") phonetic = false
            } else if (tDepth >= 0 && event in TEXT_EVENTS) { out.append(x.parser.text ?: ""); validText(out.toString()) }
        }
        return out.toString()
    }
    private fun worksheet(zip: ZipFile, path: String, strings: SharedStrings, styleCount: Int, onRow: (Int, List<Cell>) -> Unit) {
        var previousRow = 0; var rowNumber = 0; var cells: MutableMap<Int, Cell>? = null; var nextColumn = 0; var sheetDataDepth = -1; var sheetDataOpen = false
        xml(zip, path) { x ->
            x.root(MAIN_NS, "worksheet")
            while (x.next() != XmlPullParser.END_DOCUMENT) {
                if (x.event == XmlPullParser.START_TAG && x.namespace == MAIN_NS) when (x.name) {
                    "sheetData" -> { if (sheetDataDepth != -1 || x.depth != 2) bad("重复或错误位置的sheetData"); sheetDataDepth = x.depth; sheetDataOpen = true }
                    "row" -> {
                        if (cells != null || !sheetDataOpen || x.depth != sheetDataDepth + 1) bad("无效行结构")
                        rowNumber = x.attribute("r")?.let(::unsignedInt) ?: previousRow + 1
                        if (rowNumber <= previousRow || rowNumber > MAX_ROWS) bad("重复或稀疏超大行号")
                        cells = sortedMapOf(); nextColumn = 0
                    }
                    "c" -> {
                        if (!sheetDataOpen || x.depth != sheetDataDepth + 2) bad("单元格位置错误")
                        val row = cells ?: bad("单元格不在行内")
                        val ref = x.attribute("r")
                        val column = if (ref == null) nextColumn else coordinate(ref, rowNumber)
                        if (column !in 0 until MAX_COLUMNS || row.containsKey(column)) bad("重复或超量单元格")
                        row[column] = cell(x, strings, styleCount); nextColumn = column + 1
                    }
                    "mergeCells", "drawing", "oleObjects", "controls", "tableParts" -> bad("不支持合并单元格或嵌入对象")
                }
                if (x.event == XmlPullParser.END_TAG && x.name == "sheetData" && x.namespace == MAIN_NS) sheetDataOpen = false
                if (x.event == XmlPullParser.END_TAG && x.name == "row" && x.namespace == MAIN_NS) {
                    val row = cells ?: bad("无效行结束")
                    val last = row.keys.maxOrNull() ?: -1
                    onRow(rowNumber, (0..last).map { row[it] ?: Cell(CellKind.BLANK) })
                    previousRow = rowNumber; cells = null
                }
            }
            if (cells != null || sheetDataDepth < 0) bad("缺少或截断sheetData")
        }
    }
    private fun cell(x: BoundedXml, strings: SharedStrings, styleCount: Int): Cell {
        val depth = x.depth; val type = x.attribute("t")
        val style = x.attribute("s")?.let(::unsignedInt)
        if (style != null && style !in 0 until styleCount) bad("单元格样式越界")
        var value: String? = null; var inline: String? = null
        while (true) {
            val event = x.next()
            if (event == XmlPullParser.END_DOCUMENT) bad("单元格截断")
            if (event == XmlPullParser.END_TAG && x.depth == depth && x.name == "c") break
            if (event == XmlPullParser.START_TAG) {
                if (x.namespace != MAIN_NS) bad("单元格命名空间错误")
                when (x.name) {
                "v" -> { if (value != null || x.depth != depth + 1) bad("重复单元格值"); value = textElement(x) }
                "is" -> { if (inline != null || x.depth != depth + 1) bad("重复内联字符串"); inline = richText(x, "is") }
                else -> bad("不支持的单元格内容")
                }
            }
        }
        if (value != null && inline != null) bad("含糊单元格类型")
        if (type == "inlineStr") return Cell(CellKind.TEXT, inline ?: bad("内联字符串缺失"), style).also { if (value != null) bad("含糊内联字符串") }
        if (inline != null) bad("未声明内联字符串类型")
        if (value == null) { if (type !in listOf(null, "n")) bad("单元格类型缺少值"); return Cell(CellKind.BLANK, styleIndex = style) }
        val text = value
        return when (type) {
            null, "n" -> Cell(CellKind.NUMBER, text, style).also { validNumber(text) }
            "s" -> Cell(CellKind.TEXT, strings.get(unsignedInt(text)), style)
            "b" -> Cell(CellKind.BOOLEAN, text, style).also { if (text != "0" && text != "1") bad("无效布尔单元格") }
            "d" -> Cell(CellKind.ISO_DATE, text, style).also { if (text.isEmpty()) bad("空日期单元格") }
            else -> bad("未知、错误或含糊单元格类型")
        }
    }
    private fun textElement(x: BoundedXml): String {
        val depth = x.depth; val out = StringBuilder()
        while (true) {
            val event = x.next()
            if (event == XmlPullParser.END_TAG && x.depth == depth) break
            if (event == XmlPullParser.END_DOCUMENT || event == XmlPullParser.START_TAG) bad("无效文本节点")
            if (event in TEXT_EVENTS) { out.append(x.parser.text ?: ""); validText(out.toString()) }
        }
        return out.toString()
    }

    private fun validateArchive(file: File) {
        val expectedEntries = validateZipEnd(file)
        val local = linkedMapOf<String, Triple<Long, Long, Long>>()
        val normalized = hashSetOf<String>()
        var total = 0L
        ZipInputStream(file.inputStream()).use { zip ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                val name = validName(entry.name)
                if (local.size >= MAX_ENTRIES || !normalized.add(Normalizer.normalize(name.removeSuffix("/"), Normalizer.Form.NFC))) bad("重复ZIP成员或成员数超过256")
                if (!entry.isDirectory && !(name.endsWith(".xml") || name.endsWith(".rels"))) bad("不支持宏或二进制嵌入部件")
                var size = 0L
                while (true) {
                    checkCancelled(); val n = zip.read(buffer)
                    if (n < 0) break
                    size += n; total += n
                    if (size > MAX_XML || total > MAX_EXPANDED) bad("XML成员或ZIP解压总量超限")
                }
                zip.closeEntry() // ZipInputStream verifies actual CRC/size and data descriptors.
                if (size > 0 && (entry.compressedSize <= 0 || size > entry.compressedSize * 1000)) bad("ZIP压缩比超过1000:1")
                if (entry.size != size) bad("ZIP实际长度不一致")
                local[name] = Triple(size, entry.compressedSize, entry.crc)
            }
        }
        if (local.size != expectedEntries) bad("ZIP中央目录与本地成员数量不一致")
        ZipFile(file).use { zip ->
            val central = hashSetOf<String>()
            for (entry in zip.entries().asSequence()) {
                if (!central.add(entry.name) || local[entry.name] != Triple(entry.size, entry.compressedSize, entry.crc)) bad("ZIP中央目录与本地成员不一致")
            }
            if (central != local.keys || "[Content_Types].xml" !in central || "_rels/.rels" !in central) bad("不是完整OOXML工作簿")
        }
    }
    private fun validateZipEnd(file: File): Int {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < 22 || f.length() > MAX_INPUT) bad("无效ZIP大小")
            if (f.readInt() != 0x504b0304) bad("不是ZIP工作簿")
            val size = minOf(f.length(), 65557).toInt(); val tail = ByteArray(size)
            f.seek(f.length() - size); f.readFully(tail)
            fun u16(i: Int) = (tail[i].toInt() and 255) or ((tail[i + 1].toInt() and 255) shl 8)
            fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
            for (i in tail.size - 22 downTo 0) {
                if (u32(i) == 0x06054b50L && i + 22 + u16(i + 20) == tail.size) {
                    if (u16(i + 4) != 0 || u16(i + 6) != 0 || u16(i + 8) != u16(i + 10)) bad("不支持分卷ZIP")
                    val count = u16(i + 10)
                    if (count > MAX_ENTRIES || u32(i + 12) == 0xffffffffL || u32(i + 16) == 0xffffffffL) bad("不支持ZIP64或成员过多")
                    if (u32(i + 12) + u32(i + 16) != f.length() - size + i) bad("ZIP中央目录边界异常")
                    return count
                }
            }
            bad("ZIP截断或尾部存在额外字节")
        }
    }
    private fun validName(raw: String): String {
        if (raw.isEmpty() || raw.length > 1024 || raw.startsWith('/') || raw.contains('\\') || raw.contains('\u0000') || raw.contains(':')) bad("非法ZIP成员路径")
        val body = raw.removeSuffix("/")
        if (body.split('/').any { it.isEmpty() || it == "." || it == ".." } || Normalizer.normalize(raw, Normalizer.Form.NFC) != raw) bad("非规范或越界ZIP路径")
        return raw
    }
    private fun resolve(owner: String, raw: String): String {
        val uri = try { URI(raw) } catch (_: Exception) { bad("无效关系URI") }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque) bad("禁止外部或含糊关系URI")
        val path = uri.path ?: bad("空关系路径")
        val parts = if (path.startsWith('/')) mutableListOf() else owner.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { if (parts.isEmpty()) bad("关系路径越界"); parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        return validName(parts.joinToString("/"))
    }
    private inner class BoundedXml(val parser: XmlPullParser) {
        val event get() = parser.eventType
        val depth get() = parser.depth
        val namespace get() = parser.namespace.orEmpty()
        val name get() = parser.name.orEmpty()
        private var textCharacters = 0
        private var textBytes = 0
        fun attribute(name: String): String? = parser.getAttributeValue(null, name)
        fun required(name: String): String = attribute(name)?.takeIf { it.isNotEmpty() } ?: bad("XML必要属性缺失")
        fun root(ns: String, localName: String) {
            while (next() != XmlPullParser.START_TAG) if (event == XmlPullParser.END_DOCUMENT) bad("空XML部件")
            if (namespace != ns || name != localName || depth != 1) bad("XML部件根类型不支持")
        }
        fun next(): Int {
            checkCancelled()
            val event = parser.nextToken()
            if (event == XmlPullParser.DOCDECL) bad("禁止DTD和实体声明")
            if (event == XmlPullParser.ENTITY_REF && parser.name !in setOf("amp", "lt", "gt", "apos", "quot") && !parser.name.orEmpty().startsWith("#")) bad("禁止外部或自定义实体")
            if (parser.depth > 32) bad("XML嵌套深度超过32")
            if (event == XmlPullParser.START_TAG) {
                if (name.length > 128 || parser.attributeCount > 128) bad("XML名称或属性过多")
                if (name in setOf("f", "externalReference", "oleObject", "embeddedObject")) bad("禁止公式或嵌入对象")
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i).length > 128) bad("XML属性名过长")
                    validText(parser.getAttributeValue(i))
                }
            }
            if (event in TEXT_EVENTS) {
                val text = parser.text ?: bad("无法解析XML实体")
                textCharacters += text.codePointCount(0, text.length); textBytes += text.toByteArray(Charsets.UTF_8).size
                if (textCharacters > MAX_TEXT_CHARACTERS || textBytes > MAX_TEXT_BYTES) bad("XML文本节点超过限额")
            } else { textCharacters = 0; textBytes = 0 }
            return event
        }
    }
    private fun <T> xml(zip: ZipFile, path: String, block: (BoundedXml) -> T): T {
        val entry = zip.getEntry(path) ?: bad("缺少XML部件")
        if (entry.size > MAX_XML || entry.isDirectory) bad("无效XML部件")
        return zip.getInputStream(entry).use { stream ->
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            catch (unsupported: org.xmlpull.v1.XmlPullParserException) {
                // Android supports the switch; kXML2 2.3 does not implement DTD processing.
                // Only accept an unsupported switch if the parser reports it disabled.
                if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw unsupported
            }
            parser.setInput(InputStreamReader(stream, decoder))
            block(BoundedXml(parser))
        }
    }
    private fun writeXml(zip: ZipOutputStream, path: String, namespace: String, root: String, body: (XmlSerializer) -> Unit) {
        zip.putNextEntry(ZipEntry(path))
        val serializer = XmlPullParserFactory.newInstance().newSerializer()
        serializer.setOutput(zip, "UTF-8"); serializer.startDocument("UTF-8", true); serializer.setPrefix("", namespace)
        serializer.startTag(namespace, root); body(serializer); serializer.endTag(namespace, root); serializer.endDocument(); serializer.flush()
        zip.closeEntry()
    }
    private fun empty(s: XmlSerializer, namespace: String, tag: String, vararg attributes: Pair<String, String>) {
        s.startTag(namespace, tag); for ((name, value) in attributes) s.attribute("", name, value); s.endTag(namespace, tag)
    }
    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long, privateWrite: Boolean) {
        val bytes = ByteArray(65536); var total = 0L
        while (true) {
            checkCancelled(); var n = input.read(bytes)
            if (n < 0) break
            if (n == 0) { val one = input.read(); if (one < 0) break; bytes[0] = one.toByte(); n = 1 }
            total += n; if (total > maximum) bad("表格原文件超过20MiB")
            if (privateWrite) ensureSpace(n.toLong())
            output.write(bytes, 0, n)
        }
    }
    private fun <T> withTemporary(prefix: String, body: (File) -> T): T {
        if (!directory.isDirectory && !directory.mkdirs()) bad("无法创建私有表格暂存目录")
        ensureSpace(65536)
        val file = Files.createTempFile(directory.toPath(), prefix, ".tmp").toFile()
        var failure: Throwable? = null
        try { return body(file) } catch (e: Throwable) { failure = e; throw e }
        finally { try { Files.deleteIfExists(file.toPath()) } catch (cleanup: IOException) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup } }
    }
    private fun <T> protect(body: () -> T): T = try { body() }
        catch (e: IOException) { throw e }
        catch (e: org.xmlpull.v1.XmlPullParserException) { throw IOException("无效或不支持的XML部件", e) }
        catch (e: IllegalArgumentException) { throw IOException("无效表格结构", e) }
    private fun validateWrite(book: TableWorkbook) {
        if (book.sheets.isEmpty() || book.sheets.size > MAX_ENTRIES - 5 || book.styleNumberFormatIds.isEmpty() || book.styleNumberFormatIds.size > 65536) bad("无效工作簿规模")
        val names = hashSetOf<String>(); var rows = 0; var decodedBytes = 0L
        for ((id, format) in book.numberFormats) { if (id < 164 || id > 65535) bad("无效数字格式ID"); validText(format) }
        if (book.styleNumberFormatIds.any { it < 0 || it > 65535 || (it >= 164 && it !in book.numberFormats) }) bad("未知数字格式")
        for (sheet in book.sheets) {
            validSheetName(sheet.name); if (!names.add(sheet.name.lowercase(Locale.ROOT))) bad("重复工作表名称")
            rows += sheet.rows.size; if (rows > MAX_ROWS) bad("表格行数超过10000")
            for (row in sheet.rows) {
                if (row.size > MAX_COLUMNS) bad("表格列数超过128")
                for (cell in row) {
                    validText(cell.value)
                    decodedBytes += cell.value.toByteArray(Charsets.UTF_8).size
                    if (decodedBytes > MAX_EXPANDED) bad("解码单元格文本总量超过100MiB")
                    if (cell.styleIndex != null && cell.styleIndex !in book.styleNumberFormatIds.indices) bad("单元格样式越界")
                    when (cell.kind) {
                        CellKind.NUMBER -> validNumber(cell.value)
                        CellKind.BOOLEAN -> if (cell.value !in listOf("0", "1")) bad("无效布尔单元格")
                        CellKind.BLANK -> if (cell.value.isNotEmpty()) bad("空单元格包含内容")
                        CellKind.ISO_DATE -> if (cell.value.isEmpty()) bad("空日期单元格")
                        CellKind.TEXT -> Unit
                    }
                }
            }
        }
    }
    private fun validSheetName(name: String) {
        validText(name)
        if (name.isEmpty() || name.length > 31 || name.any { it in "[]:*?/\\" } || name.startsWith('\'') || name.endsWith('\'')) bad("无效工作表名称")
    }
    private fun validText(value: String) {
        if (value.codePointCount(0, value.length) > MAX_TEXT_CHARACTERS || value.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) bad("单元格文本超过限额")
        var i = 0
        while (i < value.length) {
            val c = value[i++].code
            if (c in 0xd800..0xdbff) { if (i >= value.length || value[i++].code !in 0xdc00..0xdfff) bad("文本包含无效Unicode") }
            else if (c in 0xdc00..0xdfff || (c < 32 && c !in listOf(9, 10, 13)) || c == 0xfffe || c == 0xffff) bad("文本包含非法XML字符")
        }
    }
    private fun validNumber(value: String) {
        if (value.length > 32 || !NUMBER.matches(value)) bad("无效数值字面量")
    }
    private fun unsignedInt(value: String): Int {
        if (!UINT.matches(value)) bad("无效整数属性")
        return value.toIntOrNull()?.takeIf { it >= 0 } ?: bad("整数属性溢出")
    }
    private fun coordinate(ref: String, row: Int): Int {
        val match = CELL_REF.matchEntire(ref) ?: bad("无效单元格坐标")
        if (unsignedInt(match.groupValues[2]) != row) bad("单元格行号不一致")
        var column = 0
        for (c in match.groupValues[1]) column = column * 26 + c.code - 'A'.code + 1
        return column - 1
    }
    private fun columnName(index: Int): String {
        var n = index + 1; var result = ""
        while (n > 0) { n--; result = ('A'.code + n % 26).toChar() + result; n /= 26 }
        return result
    }
    companion object {
        private const val MAX_INPUT = 20L * 1024 * 1024
        private const val MAX_EXPANDED = 100L * 1024 * 1024
        private const val MAX_XML = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 256
        private const val MAX_ROWS = 10000
        private const val MAX_COLUMNS = 128
        private const val MAX_TEXT_CHARACTERS = 32768
        private const val MAX_TEXT_BYTES = 65536
        private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CONTENT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
        private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        private const val SHEET_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
        private const val STYLES_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"
        private const val STRINGS_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"
        private const val REL_TYPE = "application/vnd.openxmlformats-package.relationships+xml"
        private val ALLOWED_TYPES = setOf(MAIN_TYPE, SHEET_TYPE, STYLES_TYPE, STRINGS_TYPE, REL_TYPE, "application/xml", "application/vnd.openxmlformats-officedocument.theme+xml", "application/vnd.openxmlformats-package.core-properties+xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml", "application/vnd.openxmlformats-officedocument.custom-properties+xml")
        private val ROOT_REL_TYPES = setOf(OFFICE + "officeDocument", OFFICE + "extended-properties", OFFICE + "custom-properties", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties")
        private val BOOK_REL_TYPES = setOf(OFFICE + "worksheet", OFFICE + "styles", OFFICE + "sharedStrings", OFFICE + "theme")
        private val TEXT_EVENTS = setOf(XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE)
        private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private val UINT = Regex("0|[1-9][0-9]{0,9}")
        private val CELL_REF = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})")
        private fun bad(message: String): Nothing = throw IOException(message)
    }
}
