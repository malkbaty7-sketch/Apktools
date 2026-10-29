package com.docshop.app

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    private var selectedName by mutableStateOf("لم يتم اختيار ملف")
    private var extractedText by mutableStateOf("")
    private var status by mutableStateOf("اختر PDF أو Word أو Excel أو TXT للبدء.")
    private var isBusy by mutableStateOf(false)

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not support persistable URI permissions.
            }
            selectedName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "document"
            readDocument(uri)
        }
    }

    private val saveTextLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    isBusy = true
                    status = "جارٍ حفظ الملف..."
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                                it.write(extractedText)
                            } ?: error("تعذر فتح ملف الحفظ")
                        }
                        status = "تم حفظ النص بنجاح."
                    } catch (e: Exception) {
                        status = "فشل الحفظ: ${e.localizedMessage ?: "خطأ غير معروف"}"
                    } finally {
                        isBusy = false
                    }
                }
            }
        }


    private val saveDocxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف Word..."
                try {
                    withContext(Dispatchers.IO) {
                        val paragraphs = extractedText.split("\n").joinToString("") { line ->
                            """<w:p><w:r><w:t xml:space="preserve">${escapeXml(line)}</w:t></w:r></w:p>"""
                        }
                        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr/></w:body></w:document>"""
                        val output = contentResolver.openOutputStream(uri) ?: error("تعذر فتح ملف الحفظ")
                        ZipOutputStream(output).use { zip ->
                            writeZipText(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
                            writeZipText(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
                            writeZipText(zip, "word/document.xml", documentXml)
                        }
                    }
                    status = "تم إنشاء ملف Word. هذا تصدير للنص ولا يحافظ على تنسيق المصدر الأصلي."
                } catch (e: Exception) {
                    status = "فشل إنشاء Word: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally { isBusy = false }
            }
        }

    private val saveCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف CSV..."
                try {
                    withContext(Dispatchers.IO) {
                        val csv = extractedText.lineSequence().joinToString("\r\n") { row ->
                            row.split('\t').joinToString(",") { cell ->
                                "\"" + cell.replace("\"", "\"\"") + "\""
                            }
                        }
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            stream.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("تعذر فتح ملف الحفظ")
                    }
                    status = "تم إنشاء ملف CSV بترميز UTF-8."
                } catch (e: Exception) {
                    status = "فشل إنشاء CSV: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally { isBusy = false }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("DocShop", style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("قارئ مستندات يعمل دون اتصال لمعظم المهام النصية",
                                style = MaterialTheme.typography.bodyLarge)
                        }

                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("فتح مستند", style = MaterialTheme.typography.titleMedium)
                                Text("يدعم استخراج النص من PDF وDOCX وXLSX وTXT. لا يتم تعديل الملف الأصلي.",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = {
                                    openFile.launch(arrayOf(
                                        "application/pdf",
                                        "text/plain",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/msword",
                                        "application/vnd.ms-excel",
                                        "application/octet-stream"
                                    ))
                                }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("اختيار ملف من الهاتف")
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("الملف الحالي", style = MaterialTheme.typography.titleMedium)
                                Text(selectedName, fontWeight = FontWeight.SemiBold)
                                Text(status, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary)
                                if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("المحتوى المستخرج", style = MaterialTheme.typography.titleLarge)
                            Text("${extractedText.length} حرف", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedTextField(
                            value = extractedText,
                            onValueChange = { extractedText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                            enabled = !isBusy,
                            label = { Text("يمكنك تعديل النص المستخرج") },
                            placeholder = { Text("سيظهر محتوى الملف هنا") },
                            minLines = 8
                        )

                        Button(onClick = { saveTextLauncher.launch("DocShop-export.txt") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("حفظ النص كملف TXT")
                        }

                        OutlinedButton(onClick = { saveDocxLauncher.launch("DocShop-export.docx") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير النص إلى Word (DOCX)")
                        }

                        OutlinedButton(onClick = { saveCsvLauncher.launch("DocShop-export.csv") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير البيانات إلى CSV")
                        }

                        OutlinedButton(onClick = {
                            selectedName = "لم يتم اختيار ملف"
                            extractedText = ""
                            status = "تم إفراغ مساحة التحرير."
                        }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("إفراغ مساحة التحرير")
                        }

                        Text(
                            "تنبيه: هذه المرحلة تستخرج النص ولا تحافظ على تصميم المستند الأصلي. ملفات DOC القديمة وXLS القديمة وملفات Access غير مدعومة بعد. PDF المصوّر يحتاج إلى OCR، وPDF المحمي بكلمة مرور قد يتعذر فتحه.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun writeZipText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun readDocument(uri: Uri) {
        lifecycleScope.launch {
            isBusy = true
            status = "جارٍ قراءة الملف..."
            try {
                val result = withContext(Dispatchers.IO) {
                    val type = contentResolver.getType(uri).orEmpty()
                    val name = queryDisplayName(uri).orEmpty()
                    val lowerName = name.lowercase()
                    when {
                        type.equals("application/pdf", true) || lowerName.endsWith(".pdf") ->
                            contentResolver.openInputStream(uri)?.use { input ->
                                PDDocument.load(input).use { PDFTextStripper().getText(it) }
                            } ?: error("تعذر فتح ملف PDF")

                        lowerName.endsWith(".docx") ||
                            type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                            contentResolver.openInputStream(uri)?.use { readDocx(it.readBytes()) }
                                ?: error("تعذر فتح ملف Word")

                        lowerName.endsWith(".xlsx") ||
                            type == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                            contentResolver.openInputStream(uri)?.use { readXlsx(it.readBytes()) }
                                ?: error("تعذر فتح ملف Excel")

                        lowerName.endsWith(".txt") || type.equals("text/plain", true) ->
                            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                ?: error("تعذر فتح الملف النصي")

                        else -> error("الصيغة غير مدعومة بعد. جرّب PDF أو DOCX أو XLSX أو TXT.")
                    }
                }
                extractedText = result
                status = if (result.isBlank()) {
                    "لم يُعثر على نص. قد يكون الملف فارغًا أو يحتوي على صور فقط."
                } else {
                    "تم استخراج النص. راجعه قبل حفظ نسخة TXT."
                }
            } catch (e: Exception) {
                status = "تعذرت قراءة الملف: ${e.localizedMessage ?: "صيغة غير مدعومة أو ملف تالف/محمي"}"
            } finally {
                isBusy = false
            }
        }
    }

    private fun readDocx(bytes: ByteArray): String {
        val documentXml = readZipEntry(bytes, "word/document.xml")
            ?: error("ملف DOCX غير صالح: لم يتم العثور على محتوى المستند")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(documentXml))
        val output = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "t") {
                output.append(parser.nextText())
            } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                output.append('\n')
            }
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        return output.toString().trimEnd()
    }

    private fun readXlsx(bytes: ByteArray): String {
        val sharedStringsXml = readZipEntry(bytes, "xl/sharedStrings.xml")
        val sharedStrings = if (sharedStringsXml != null) parseSharedStrings(sharedStringsXml) else emptyList()
        val sheetXml = readZipEntry(bytes, "xl/worksheets/sheet1.xml")
            ?: error("لم يتم العثور على ورقة العمل الأولى في ملف XLSX")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(sheetXml))
        val output = StringBuilder()
        var inRow = false
        var inValue = false
        var inInlineText = false
        var cellRef = ""
        var cellType = ""
        var cellValue = StringBuilder()
        var rowCells = sortedMapOf<Int, String>()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> { inRow = true; rowCells = sortedMapOf() }
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r") ?: ""
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        cellValue = StringBuilder()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> if (inValue || inInlineText) cellValue.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = cellValue.toString()
                        val value = if (cellType == "s") {
                            sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                        } else raw
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
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        return output.toString().trimEnd()
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val strings = mutableListOf<String>()
        var inItem = false
        var inText = false
        val current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inItem = true; current.setLength(0) }
                    "t" -> if (inItem) inText = true
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> { strings.add(current.toString()); inItem = false }
                }
            }
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        return strings
    }

    private fun readZipEntry(bytes: ByteArray, wanted: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == wanted) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return null
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
