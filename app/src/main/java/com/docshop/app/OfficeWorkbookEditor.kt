package com.docshop.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Applies text-grid edits to an existing XLSX package while retaining unrelated
 * workbook parts, worksheets, styles, relationships, drawings and metadata.
 *
 * Formula cells are retained when their displayed cached value was not changed.
 * A cell edit beginning with '=' is written as a formula for recalculation by
 * spreadsheet software when the workbook is opened.
 */
object OfficeWorkbookEditor {
    private data class Sheet(val name: String, val path: String)

    fun updateXlsx(original: ByteArray, editedText: String): ByteArray {
        require(original.isNotEmpty()) { "ملف Excel الأصلي فارغ" }
        val entries = readPackage(original)
        val workbook = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)
            ?: error("ملف XLSX غير صالح: لم يتم العثور على workbook.xml")
        val relationships = entries["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
            ?: error("ملف XLSX غير صالح: تعذر قراءة علاقات أوراق العمل")
        val sheets = readSheets(workbook, relationships)
        require(sheets.isNotEmpty()) { "لم يتم العثور على أوراق عمل قابلة للتحرير" }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)
            ?.let(::readSharedStrings).orEmpty()
        val sections = splitSections(editedText, sheets.map { it.name })
        var changedCells = 0

        sheets.forEachIndexed { sheetIndex, sheet ->
            val sheetBytes = entries[sheet.path] ?: return@forEachIndexed
            val content = sections[sheet.name] ?: if (sheets.size == 1) editedText else return@forEachIndexed
            val updated = updateSheet(sheetBytes.toString(Charsets.UTF_8), content, sharedStrings)
            changedCells += updated.second
            entries[sheet.path] = updated.first.toByteArray(Charsets.UTF_8)
        }
        require(changedCells > 0 || editedText.isBlank()) {
            "لم يتم العثور على خلايا قابلة للتحديث في أوراق Excel. لم يتم تغيير الملف الأصلي."
        }

        val recalculationWorkbook = workbook.replace(
            Regex("<calcPr\\b([^>]*)/?>")
        ) { match ->
            val attrs = match.groupValues[1]
                .replace(Regex("""\s+(fullCalcOnLoad|forceFullCalc|calcMode)="[^"]*"""" )) { "" }
            "<calcPr$attrs fullCalcOnLoad=\"1\" forceFullCalc=\"1\" calcMode=\"auto\"/>"
        }.let { updated ->
            if (updated.contains("<calcPr")) updated
            else updated.replace("</workbook>", "<calcPr fullCalcOnLoad=\"1\" forceFullCalc=\"1\" calcMode=\"auto\"/></workbook>")
        }
        entries["xl/workbook.xml"] = recalculationWorkbook.toByteArray(Charsets.UTF_8)

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

    private fun readPackage(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(result.size < 5000) { "ملف Excel يحتوي على عدد كبير جدًا من المكونات" }
                    val data = zip.readBytes()
                    total += data.size
                    require(data.size <= 32 * 1024 * 1024 && total <= 128L * 1024 * 1024) {
                        "حجم مكونات ملف Excel يتجاوز الحد الآمن للتحرير"
                    }
                    result[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun readSheets(workbook: String, relationships: String): List<Sheet> {
        val relationMap = linkedMapOf<String, String>()
        Regex("<Relationship\\b([^>]*)/?>").findAll(relationships).forEach { match ->
            val attrs = match.groupValues[1]
            val id = attribute(attrs, "Id")
            val target = attribute(attrs, "Target")
            if (id.isNotBlank() && target.isNotBlank()) {
                val path = when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }.replace(Regex("/\\./"), "/")
                relationMap[id] = path
            }
        }
        return Regex("<sheet\\b([^>]*)/?>").findAll(workbook).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val name = decodeXml(attribute(attrs, "name"))
            val relId = attribute(attrs, "id")
            val path = relationMap[relId] ?: return@mapNotNull null
            if (name.isBlank()) null else Sheet(name, path)
        }.toList()
    }

    private fun splitSections(text: String, sheetNames: List<String>): Map<String, String> {
        if (sheetNames.size == 1) return mapOf(sheetNames.first() to text)
        val result = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        text.lineSequence().forEach { line ->
            val marker = if (line.startsWith("【") && line.endsWith("】") && line.length > 2) {
                line.substring(1, line.length - 1)
            } else null
            if (marker != null && marker in sheetNames) {
                current = marker
                result.getOrPut(marker) { StringBuilder() }
            } else {
                current?.let { name ->
                    val builder = result.getOrPut(name) { StringBuilder() }
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(line)
                }
            }
        }
        return result.mapValues { it.value.toString() }
    }

    private fun readSharedStrings(xml: String): List<String> =
        Regex("<si\\b[^>]*>([\\s\\S]*?)</si>").findAll(xml).map { item ->
            Regex("<t\\b[^>]*>([\\s\\S]*?)</t>").findAll(item.groupValues[1])
                .joinToString("") { decodeXml(it.groupValues[1]) }
        }.toList()

    private fun updateSheet(xml: String, text: String, sharedStrings: List<String>): Pair<String, Int> {
        val rows = text.split('\n')
        var rowIndex = 0
        var changed = 0
        val rowRegex = Regex("<row\\b([^>]*)>([\\s\\S]*?)</row>")
        val updated = rowRegex.replace(xml) { rowMatch ->
            val targetCells = rows.getOrNull(rowIndex)?.removeSuffix("\r")?.split('\t').orEmpty()
            rowIndex++
            if (targetCells.isEmpty()) return@replace rowMatch.value

            val attrs = rowMatch.groupValues[1]
            var rowBody = rowMatch.groupValues[2]
            val presentColumns = mutableSetOf<Int>()
            val cellRegex = Regex("<c\\b([^>]*)>([\\s\\S]*?)</c>")
            rowBody = cellRegex.replace(rowBody) { cellMatch ->
                val cellAttrs = cellMatch.groupValues[1]
                val reference = attribute(cellAttrs, "r")
                val column = columnIndex(reference)
                if (column < 0) return@replace cellMatch.value
                presentColumns.add(column)
                val desired = targetCells.getOrNull(column) ?: return@replace cellMatch.value
                val oldInner = cellMatch.groupValues[2]
                val type = attribute(cellAttrs, "t")
                val oldValue = when (type) {
                    "s" -> {
                        val index = Regex("<v>([\\s\\S]*?)</v>").find(oldInner)?.groupValues?.get(1)?.toIntOrNull()
                        sharedStrings.getOrNull(index ?: -1).orEmpty()
                    }
                    "inlineStr" -> Regex("<t\\b[^>]*>([\\s\\S]*?)</t>").find(oldInner)
                        ?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                    else -> Regex("<v>([\\s\\S]*?)</v>").find(oldInner)?.groupValues?.get(1).orEmpty()
                }
                val hasFormula = Regex("<f(?:\\s|>)").containsMatchIn(oldInner)
                if (hasFormula && desired == oldValue) return@replace cellMatch.value
                if (desired == oldValue) return@replace cellMatch.value
                changed++
                val newAttrs = setCellType(cellAttrs, desired)
                val newInner = when {
                    desired.startsWith("=") -> {
                        val formula = desired.drop(1).removePrefix("=")
                        "<f>${escapeXml(formula)}</f><v></v>"
                    }
                    isNumber(desired) -> "<v>${desired}</v>"
                    else -> "<is><t xml:space=\"preserve\">${escapeXml(desired)}</t></is>"
                }
                "<c$newAttrs>$newInner</c>"
            }

            // Add values entered into cells that were previously absent.
            val rowNumber = attribute(attrs, "r").toIntOrNull() ?: rowIndex
            val additions = targetCells.mapIndexedNotNull { column, value ->
                if (column in presentColumns || value.isEmpty()) null
                else {
                    changed++
                    val ref = columnName(column + 1) + rowNumber
                    val cell = if (value.startsWith("=")) {
                        "<c r=\"$ref\"><f>${escapeXml(value.drop(1))}</f><v></v></c>"
                    } else if (isNumber(value)) {
                        "<c r=\"$ref\"><v>$value</v></c>"
                    } else {
                        "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
                    }
                    cell
                }
            }.joinToString("")
            rowBody += additions
            "<row$attrs>$rowBody</row>"
        }
        return updated to changed
    }

    private fun setCellType(attrs: String, value: String): String {
        val cleaned = attrs.replace(Regex("""\s+t="[^"]*"""" )) { "" }
        return if (value.startsWith("=") || isNumber(value)) cleaned
        else cleaned + " t=\"inlineStr\""
    }

    private fun attribute(attrs: String, name: String): String =
        Regex("""(?:^|\s)$name="([^"]*)"""" ).find(attrs)?.groupValues?.get(1).orEmpty()

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

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.toString()
    }

    private fun isNumber(value: String): Boolean =
        value.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
