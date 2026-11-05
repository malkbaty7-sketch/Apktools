package com.docshop.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Conservative text extraction for OOXML Word/Excel packages.
 * It extracts visible text and cached spreadsheet values; it does not render Office layout.
 */
object OfficeDocumentReader {
    private const val MAX_ENTRIES = 4000
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < MAX_ENTRIES) { "ملف Office يحتوي على عدد كبير جدًا من المكونات" }
                    val data = zip.readBytes()
                    total += data.size
                    require(data.size <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) {
                        "حجم مكونات ملف Office يتجاوز الحد الآمن للقراءة"
                    }
                    entries[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun parser(xml: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml.toString(Charsets.UTF_8)))
        }

    fun readDocx(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val main = parts["word/document.xml"]
            ?: error("ملف DOCX غير صالح: لم يتم العثور على محتوى المستند")
        val output = StringBuilder(readWordPart(main))
        val supplementary = parts.keys.filter {
            it.matches(Regex("word/(header|footer)[^/]*\\.xml")) ||
                it == "word/footnotes.xml" || it == "word/endnotes.xml"
        }.sorted()
        supplementary.forEach { name ->
            val content = readWordPart(parts.getValue(name)).trim()
            if (content.isNotEmpty()) {
                val label = when {
                    name.contains("header") -> "رأس الصفحة"
                    name.contains("footer") -> "تذييل الصفحة"
                    name.contains("footnotes") -> "الحواشي السفلية"
                    else -> "الحواشي الختامية"
                }
                output.append("\n\n【").append(label).append("】\n").append(content)
            }
        }
        return output.toString().trim()
    }

    private fun readWordPart(xml: ByteArray): String {
        val p = parser(xml)
        val out = StringBuilder()
        var tableDepth = 0
        var deletedDepth = 0
        var inText = false
        var rowDepth = 0
        var cellDepth = 0
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "del" -> deletedDepth++
                    "tbl" -> tableDepth++
                    "tr" -> rowDepth++
                    "tc" -> cellDepth++
                    "t" -> inText = true
                    "tab" -> if (deletedDepth == 0) out.append('\t')
                    "br", "cr" -> if (deletedDepth == 0) out.append('\n')
                }
                XmlPullParser.TEXT -> if (inText && deletedDepth == 0) out.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "del" -> deletedDepth = (deletedDepth - 1).coerceAtLeast(0)
                    "t" -> inText = false
                    "tc" -> {
                        cellDepth = (cellDepth - 1).coerceAtLeast(0)
                        if (deletedDepth == 0) {
                            while (out.endsWith("\n")) out.setLength(out.length - 1)
                            out.append('\t')
                        }
                    }
                    "tr" -> {
                        rowDepth = (rowDepth - 1).coerceAtLeast(0)
                        if (deletedDepth == 0) {
                            while (out.endsWith("\t") || out.endsWith("\n")) out.setLength(out.length - 1)
                            out.append('\n')
                        }
                    }
                    "p" -> if (deletedDepth == 0) {
                        if (!out.endsWith("\n")) out.append('\n')
                    }
                    "tbl" -> {
                        tableDepth = (tableDepth - 1).coerceAtLeast(0)
                        if (deletedDepth == 0 && !out.endsWith("\n")) out.append('\n')
                    }
                }
            }
            event = p.next()
        }
        return out.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun readXlsx(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        val dateStyles = readDateStyleIndexes(parts["xl/styles.xml"])
        val uses1904Dates = parts["xl/workbook.xml"]?.let(::uses1904DateSystem) ?: false
        val relationships = readWorkbookRelationships(parts["xl/_rels/workbook.xml.rels"])
        val sheets = readWorkbookSheets(parts["xl/workbook.xml"], relationships)
        if (sheets.isEmpty()) {
            val fallback = parts["xl/worksheets/sheet1.xml"]
                ?: error("لم يتم العثور على ورقة عمل في ملف XLSX")
            return readSheet(fallback, shared, dateStyles, uses1904Dates)
        }
        val contents = sheets.mapNotNull { (name, path) ->
            val xml = parts[path] ?: return@mapNotNull null
            val content = readSheet(xml, shared, dateStyles, uses1904Dates)
            if (sheets.size == 1) content else "【$name】\n$content"
        }
        return contents.joinToString("\n\n").ifBlank {
            error("لم يتم العثور على بيانات قابلة للقراءة في أوراق Excel")
        }
    }

    private fun readWorkbookRelationships(xml: ByteArray?): Map<String, String> {
        if (xml == null) return emptyMap()
        val p = parser(xml)
        val result = linkedMapOf<String, String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = attribute(p, "Id")
                val target = attribute(p, "Target")
                val external = attribute(p, "TargetMode") == "External"
                if (id.isNotBlank() && target.isNotBlank() && !external) {
                    result[id] = resolveWorkbookTarget(target)
                }
            }
            event = p.next()
        }
        return result
    }

    private fun resolveWorkbookTarget(target: String): String {
        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        val normalized = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                else -> normalized.addLast(segment)
            }
        }
        return normalized.joinToString("/")
    }

    private fun readWorkbookSheets(
        xml: ByteArray?,
        relationships: Map<String, String>
    ): List<Pair<String, String>> {
        if (xml == null) return emptyList()
        val p = parser(xml)
        val result = mutableListOf<Pair<String, String>>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = attribute(p, "name").ifBlank { "ورقة ${result.size + 1}" }
                val relId = attribute(p, "id")
                relationships[relId]?.let { result.add(name to it) }
            }
            event = p.next()
        }
        return result
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val p = parser(xml)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false
        var skipPhonetic = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inItem = true; current.setLength(0) }
                    "rPh" -> skipPhonetic = true
                    "t" -> if (inItem && !skipPhonetic) inText = true
                }
                XmlPullParser.TEXT -> if (inItem && inText && !skipPhonetic) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "rPh" -> skipPhonetic = false
                    "si" -> { result.add(current.toString()); inItem = false }
                }
            }
            event = p.next()
        }
        return result
    }

    private fun readDateStyleIndexes(xml: ByteArray?): Set<Int> {
        if (xml == null) return emptySet()
        val customFormats = mutableMapOf<Int, String>()
        val cellFormats = mutableListOf<Int>()
        val p = parser(xml)
        var inCellXfs = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "numFmt" -> {
                        val id = attribute(p, "numFmtId").toIntOrNull()
                        val format = attribute(p, "formatCode")
                        if (id != null) customFormats[id] = format
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) cellFormats.add(attribute(p, "numFmtId").toIntOrNull() ?: 0)
                }
                XmlPullParser.END_TAG -> if (p.name == "cellXfs") inCellXfs = false
            }
            event = p.next()
        }
        return cellFormats.mapIndexedNotNull { index, id ->
            val builtinDate = id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58
            val custom = customFormats[id].orEmpty()
            val cleaned = custom.replace(Regex("\\[[^]]*]"), "")
                .replace(Regex("\"[^\"]*\""), "")
                .replace(Regex("\\\\."), "")
            val hasDatePattern = cleaned.contains(Regex("(?i)[yd]")) ||
                (cleaned.contains(Regex("(?i)h")) && cleaned.contains(Regex("(?i)m")))
            if (builtinDate || hasDatePattern) index else null
        }.toSet()
    }

    private fun uses1904DateSystem(xml: ByteArray): Boolean {
        val p = parser(xml)
        var result = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "workbookPr") {
                result = attribute(p, "date1904").let { it == "1" || it.equals("true", true) }
                break
            }
            event = p.next()
        }
        return result
    }

    private fun formatExcelDate(serialText: String, date1904: Boolean, format: String): String {
        val serial = serialText.toDoubleOrNull() ?: return serialText
        if (!serial.isFinite()) return serialText
        return try {
            val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0)
                else LocalDateTime.of(1899, 12, 30, 0, 0)
            val date = base.plusSeconds((serial * 86400.0).toLong())
            val pattern = if (format.contains(Regex("(?i)[hs]"))) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd"
            date.format(DateTimeFormatter.ofPattern(pattern))
        } catch (_: Exception) { serialText }
    }

    private fun readSheet(
        xml: ByteArray,
        shared: List<String>,
        dateStyles: Set<Int>,
        date1904: Boolean
    ): String {
        val p = parser(xml)
        val output = StringBuilder()
        var inRow = false
        var inValue = false
        var inInlineText = false
        var inFormula = false
        var cellRef = ""
        var cellType = ""
        var styleIndex = -1
        var cellValue = StringBuilder()
        var formula = StringBuilder()
        var rowCells = sortedMapOf<Int, String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { inRow = true; rowCells = sortedMapOf() }
                    "c" -> {
                        cellRef = attribute(p, "r")
                        cellType = attribute(p, "t")
                        styleIndex = attribute(p, "s").toIntOrNull() ?: -1
                        cellValue = StringBuilder()
                        formula = StringBuilder()
                    }
                    "v" -> inValue = true
                    "f" -> inFormula = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> when {
                    inFormula -> formula.append(p.text)
                    inValue || inInlineText -> cellValue.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "f" -> inFormula = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = cellValue.toString()
                        val value = when (cellType) {
                            "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            "b" -> if (raw == "1") "TRUE" else if (raw == "0") "FALSE" else raw
                            "e" -> raw
                            "str", "inlineStr", "d" -> raw
                            else -> if (styleIndex in dateStyles) formatExcelDate(raw, date1904, "") else raw
                        }.ifBlank {
                            if (formula.isNotBlank()) "=${formula}" else ""
                        }
                        val column = columnIndex(cellRef)
                        if (column >= 0 && column <= 16383) rowCells[column] = value
                    }
                    "row" -> if (inRow) {
                        val maxColumn = rowCells.keys.maxOrNull() ?: -1
                        if (maxColumn >= 0) {
                            for (column in 0..maxColumn) {
                                if (column > 0) output.append('\t')
                                output.append(rowCells[column].orEmpty())
                            }
                        }
                        output.append('\n')
                        inRow = false
                    }
                }
            }
            event = p.next()
        }
        return output.toString().trimEnd()
    }

    private fun attribute(p: XmlPullParser, localName: String): String {
        for (index in 0 until p.attributeCount) {
            if (p.getAttributeName(index) == localName || p.getAttributeName(index).substringAfter(':') == localName) {
                return p.getAttributeValue(index).orEmpty()
            }
        }
        return ""
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        var found = false
        for (char in reference) {
            if (!char.isLetter()) break
            found = true
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return if (found) result - 1 else -1
    }
}
