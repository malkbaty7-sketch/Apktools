package com.docshop.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Updates the text layer of an existing DOCX package. Existing package parts,
 * paragraph properties, table structure, images, headers, footers and relationships
 * are retained. Within an edited paragraph/cell, text is placed in its first run;
 * mixed run-level formatting inside that paragraph may therefore be simplified.
 */
object OfficeDocumentEditor {
    fun updateDocx(original: ByteArray, editedText: String): ByteArray {
        require(original.isNotEmpty()) { "ملف Word الأصلي فارغ" }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(original)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < 5000) { "ملف Word يحتوي على عدد كبير جدًا من المكونات" }
                    val data = zip.readBytes()
                    total += data.size
                    require(data.size <= 32 * 1024 * 1024 && total <= 128L * 1024 * 1024) {
                        "حجم مكونات ملف Word يتجاوز الحد الآمن للتحرير"
                    }
                    entries[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        val path = "word/document.xml"
        val document = entries[path]?.toString(Charsets.UTF_8)
            ?: error("ملف DOCX غير صالح: لم يتم العثور على document.xml")
        val bodyText = editedText.substringBefore("\n\n【رأس الصفحة】")
            .substringBefore("\n\n【تذييل الصفحة】")
        val lines = bodyText.split('\n')
        var lineIndex = 0
        val blockRegex = Regex("<w:tbl\\b[\\s\\S]*?</w:tbl>|<w:p\\b[^>]*>[\\s\\S]*?</w:p>")
        var updated = blockRegex.replace(document) { block ->
            if (block.value.startsWith("<w:tbl")) {
                val table = block.value
                val rowRegex = Regex("<w:tr\\b([^>]*)>([\\s\\S]*?)</w:tr>")
                val changedTable = rowRegex.replace(table) { row ->
                    val target = lines.getOrNull(lineIndex++).orEmpty().split('\t')
                    val cellRegex = Regex("<w:tc\\b([^>]*)>([\\s\\S]*?)</w:tc>")
                    var cellIndex = 0
                    val cells = cellRegex.replace(row.groupValues[2]) { cell ->
                        val value = target.getOrNull(cellIndex++).orEmpty()
                        val cellBody = replaceParagraphTexts(cell.groupValues[2], value)
                        "<w:tc${cell.groupValues[1]}>$cellBody</w:tc>"
                    }
                    "<w:tr${row.groupValues[1]}>$cells</w:tr>"
                }
                changedTable
            } else {
                val line = lines.getOrNull(lineIndex++).orEmpty()
                replaceParagraphTexts(block.value, line)
            }
        }
        if (lineIndex < lines.size) {
            val extra = lines.drop(lineIndex).joinToString("") { line ->
                val safe = escapeXml(line)
                """<w:p><w:pPr><w:bidi/><w:jc w:val="right"/></w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space="preserve">$safe</w:t></w:r></w:p>"""
            }
            updated = if (updated.contains("<w:sectPr")) {
                updated.replaceFirst("<w:sectPr", extra + "<w:sectPr")
            } else {
                updated.replace("</w:body>", extra + "</w:body>")
            }
        }
        entries[path] = updated.toByteArray(Charsets.UTF_8)

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun replaceParagraphTexts(xml: String, value: String): String {
        var found = false
        val textTag = Regex("<w:t\\b([^>]*)>([\\s\\S]*?)</w:t>")
        val updated = textTag.replace(xml) { match ->
            if (!found) {
                found = true
                "<w:t${match.groupValues[1]}>${escapeXml(value)}</w:t>"
            } else {
                "<w:t${match.groupValues[1]}></w:t>"
            }
        }
        if (found) return updated
        val run = """<w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space="preserve">${escapeXml(value)}</w:t></w:r>"""
        return if (updated.contains("</w:p>")) updated.replaceFirst("</w:p>", run + "</w:p>") else updated
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
