package com.docshop.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/** Reads visible paragraph and table text from ODT, ODS and ODP OpenDocument packages. */
object OpenDocumentReader {
    fun read(bytes: ByteArray): String {
        val content = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: ByteArray? = null
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val data = zip.readBytes()
                    total += data.size
                    require(data.size <= 24 * 1024 * 1024 && total <= 96L * 1024 * 1024) {
                        "حجم مكونات مستند OpenDocument يتجاوز الحد الآمن"
                    }
                    if (entry.name == "content.xml") found = data
                }
                zip.closeEntry()
            }
            found
        } ?: error("ملف OpenDocument غير صالح: لم يتم العثور على content.xml")
        return extractContent(content)
    }

    private fun extractContent(xml: ByteArray): String {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml.toString(Charsets.UTF_8)))
        }
        val out = StringBuilder()
        var inParagraph = false
        var inText = false
        var inRow = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p", "h" -> inParagraph = true
                    "tab" -> if (inParagraph) out.append('\t')
                    "line-break" -> if (inParagraph) out.append('\n')
                    "s" -> if (inParagraph) {
                        val count = attribute(parser, "c").toIntOrNull()?.coerceIn(1, 1000) ?: 1
                        repeat(count) { out.append(' ') }
                    }
                    "table-row" -> inRow = true
                    "t" -> if (inParagraph) inText = true
                }
                XmlPullParser.TEXT -> if (inParagraph && inText) out.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p", "h" -> {
                        inParagraph = false
                        if (!out.endsWith('\n')) out.append('\n')
                    }
                    "table-cell", "covered-table-cell" -> if (inRow) out.append('\t')
                    "table-row" -> if (inRow) {
                        while (out.endsWith('\t') || out.endsWith('\n')) out.setLength(out.length - 1)
                        out.append('\n')
                        inRow = false
                    }
                }
            }
            event = parser.next()
        }
        return out.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun attribute(parser: XmlPullParser, localName: String): String {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index)
            if (name == localName || name.substringAfter(':') == localName) return parser.getAttributeValue(index).orEmpty()
        }
        return ""
    }
}
