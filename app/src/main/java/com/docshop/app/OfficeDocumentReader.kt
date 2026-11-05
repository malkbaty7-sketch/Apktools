package com.docshop.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/** Extracts text and tabular values from DOCX/XLSX without changing source files. */
object OfficeDocumentReader {
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < 4000) { "ملف Office يحتوي على عدد كبير جدًا من المكونات" }
                    val data = zip.readBytes()
                    require(data.size <= 32 * 1024 * 1024) { "أحد مكونات الملف كبير جدًا" }
                    entries[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun parser(xml: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(StringReader(xml.toString(Charsets.UTF_8))) }

    fun readDocx(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val main = parts["word/document.xml"]
            ?: error("ملف DOCX غير صالح: لم يتم العثور على محتوى المستند")
        val output = StringBuilder(readWordPart(main))
        val supplementary = parts.keys
            .filter { it.matches(Regex("word/(header|footer)[^/]*\\.xml")) }
            .sorted()
        supplementary.forEach { name ->
            val content = readWordPart(parts.getValue(name)).trim()
            if (content.isNotEmpty()) {
                output.append("\n\n【")
                    .append(if (name.contains("header")) "رأس الصفحة" else "تذييل الصفحة")
                    .append("】\n").append(content)
            }
        }
        return output.toString().trim()
    }

    private fun readWordPart(xml: ByteArray): String {
        val p = parser(xml)
        val out = StringBuilder()
        var inTable = false
        var inCell = false
        var inText = false
        var deletedDepth = 0
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "del" -> deletedDepth++
                    "tbl" -> inTable = true
                    "tc" -> inCell = true
                    "t" -> inText = true
                    "tab" -> if (deletedDepth == 0) out.append('\t')
                    "br", "cr" -> if (deletedDepth == 0) out.append('\n')
                }
                XmlPullParser.TEXT -> if (inText && deletedDepth == 0) out.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "del" -> deletedDepth = (deletedDepth - 1).coerceAtLeast(0)
                    "t" -> inText = false
                    "tc" -> { inCell = false; out.append('\t') }
                    "tr" -> {
                        if (out.endsWith("\t")) out.setLength(out.length - 1)
                        out.append('\n')
                    }
                    "p" -> if (!inTable || !inCell) out.append('\n') else out.append(' ')
                    "tbl" -> {
                        inTable = false
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                    }
                }
            }
            event = p.next()
        }
        return out.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
    }

    fun readXlsx(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let { readSharedStrings(it) }.orEmpty()
        val relationships = readWorkbookRelationships(parts["xl/_rels/workbook.xml.rels"])
        val sheets = readWorkbookSheets(parts["xl/workbook.xml"], relationships)
        if (sheets.isEmpty()) {
            val fallback = parts["xl/worksheets/sheet1.xml"]
                ?: error("لم يتم العثور على ورقة عمل في ملف XLSX")
            return readSheet(fallback, shared)
        }
        return sheets.mapNotNull { (name, path) ->
            val xml = parts[path] ?: return@mapNotNull null
            val content = readSheet(xml, shared)
            if (sheets.size == 1) content else "【" + name + "】\n" + content
        }.joinToString("\n\n").ifBlank {
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
                if (id.isNotBlank() && target.isNotBlank()) {
                    val normalized = when {
                        target.startsWith("/") -> target.removePrefix("/")
                        target.startsWith("xl/") -> target
                        else -> "xl/" + target
                    }
                    result[id] = normalized
                }
            }
            event = p.next()
        }
        return result
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
                val name = attribute(p, "name").ifBlank { "ورقة " + (result.size + 1) }
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

    private fun readSheet(xml: ByteArray, shared: List<String>): String {
        val p = parser(xml)
        val output = StringBuilder()
        var inRow = false
        var inValue = false
        var inInlineText = false
        var cellRef = ""
        var cellType = ""
        var cellValue = StringBuilder()
        var rowCells = sortedMapOf<Int, String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { inRow = true; rowCells = sortedMapOf() }
                    "c" -> {
                        cellRef = attribute(p, "r")
                        cellType = attribute(p, "t")
                        cellValue = StringBuilder()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> if (inValue || inInlineText) cellValue.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = cellValue.toString()
                        val value = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                        val column = columnIndex(cellRef)
                        if (column >= 0) rowCells[column] = value
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
            if (p.getAttributeName(index) == localName) return p.getAttributeValue(index).orEmpty()
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
