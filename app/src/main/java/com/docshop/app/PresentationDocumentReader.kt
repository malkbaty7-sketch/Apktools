package com.docshop.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/** Text extraction for PowerPoint Open XML presentations. Slide order follows presentation.xml relationships. */
object PresentationDocumentReader {
    fun readPptx(bytes: ByteArray): String {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < 3000) { "العرض يحتوي على عدد كبير جدًا من المكونات" }
                    val data = zip.readBytes()
                    total += data.size
                    require(data.size <= 24 * 1024 * 1024 && total <= 96L * 1024 * 1024) {
                        "حجم مكونات العرض يتجاوز الحد الآمن للقراءة"
                    }
                    entries[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        val relationships = readRelationships(entries["ppt/_rels/presentation.xml.rels"])
        val slidePaths = readSlideOrder(entries["ppt/presentation.xml"], relationships)
            .ifEmpty {
                entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.substringAfter("slide").substringBefore(".").toIntOrNull() ?: Int.MAX_VALUE }
            }
        require(slidePaths.isNotEmpty()) { "لم يتم العثور على شرائح قابلة للقراءة في العرض" }
        return slidePaths.mapIndexedNotNull { index, path ->
            val xml = entries[path] ?: return@mapIndexedNotNull null
            val text = readSlide(xml)
            if (text.isBlank()) null else "【الشريحة " + (index + 1) + "】\n" + text
        }.joinToString("\n\n").ifBlank { "لم يتم العثور على نص داخل شرائح العرض." }
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(bytes.toString(Charsets.UTF_8)))
        }

    private fun readRelationships(xml: ByteArray?): Map<String, String> {
        if (xml == null) return emptyMap()
        val p = parser(xml)
        val result = linkedMapOf<String, String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = attribute(p, "Id")
                val target = attribute(p, "Target")
                if (id.isNotBlank() && target.isNotBlank() && attribute(p, "TargetMode") != "External") {
                    result[id] = normalizeTarget(target)
                }
            }
            event = p.next()
        }
        return result
    }

    private fun readSlideOrder(xml: ByteArray?, relationships: Map<String, String>): List<String> {
        if (xml == null) return emptyList()
        val p = parser(xml)
        val result = mutableListOf<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "sldId") {
                relationships[attribute(p, "id")]?.let(result::add)
            }
            event = p.next()
        }
        return result
    }

    private fun normalizeTarget(target: String): String {
        val path = if (target.startsWith("/")) target.removePrefix("/") else "ppt/$target"
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(it)
            }
        }
        return parts.joinToString("/")
    }

    private fun readSlide(xml: ByteArray): String {
        val p = parser(xml)
        val out = StringBuilder()
        var inText = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "t" -> inText = true
                    "br" -> out.append('\n')
                    "tab" -> out.append('\t')
                }
                XmlPullParser.TEXT -> if (inText) out.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "p" -> if (!out.endsWith('\n')) out.append('\n')
                    "tc" -> out.append('\t')
                    "tr" -> {
                        while (out.endsWith('\t') || out.endsWith('\n')) out.setLength(out.length - 1)
                        out.append('\n')
                    }
                }
            }
            event = p.next()
        }
        return out.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
    }

    private fun attribute(p: XmlPullParser, localName: String): String {
        for (index in 0 until p.attributeCount) {
            val name = p.getAttributeName(index)
            if (name == localName || name.substringAfter(':') == localName) return p.getAttributeValue(index).orEmpty()
        }
        return ""
    }
}
