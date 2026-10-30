package com.docshop.app

import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.googlecode.tesseract.android.TessBaseAPI

class MainActivity : ComponentActivity() {
    private var selectedName by mutableStateOf("لم يتم اختيار ملف")
    private var extractedText by mutableStateOf("")
    private var status by mutableStateOf("اختر PDF أو Word أو Excel أو TXT للبدء.")
    private var isBusy by mutableStateOf(false)
    private var showPasswordDialog by mutableStateOf(false)
    private var showClearTextDialog by mutableStateOf(false)
    private var showDeleteDraftDialog by mutableStateOf(false)
    private var showExcelCellDialog by mutableStateOf(false)
    private var editingExcelRow by mutableIntStateOf(0)
    private var editingExcelColumn by mutableIntStateOf(0)
    private var excelCellValue by mutableStateOf("")
    private var previousExcelText by mutableStateOf<String?>(null)
    private var previousTextEdit by mutableStateOf<String?>(null)
    private var redoTextEdit by mutableStateOf<String?>(null)
    private var documentSearchQuery by mutableStateOf("")
    private var replaceSearchText by mutableStateOf("")
    private var replaceWithText by mutableStateOf("")
    private var replaceStatus by mutableStateOf("")
    private var pdfPassword by mutableStateOf("")
    private var pendingPdfBytes: ByteArray? = null
    private var selectedPdfUri: Uri? = null
    private var pdfPageBitmap by mutableStateOf<Bitmap?>(null)
    private var pdfPageCount by mutableIntStateOf(0)
    private var pdfPageIndex by mutableIntStateOf(0)
    private var pdfZoom by mutableFloatStateOf(1f)
    private var pdfViewerStatus by mutableStateOf("")
    private var draftSavedAt by mutableLongStateOf(0L)
    private val recentDocuments = mutableStateListOf<Pair<String, String>>()

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not support persistable URI permissions.
            }
            selectedName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "document"
            selectedPdfUri = if (selectedName.lowercase().endsWith(".pdf") || contentResolver.getType(uri).equals("application/pdf", true)) uri else null
            rememberRecentDocument(uri, selectedName)
            readDocument(uri)
            if (selectedPdfUri != null) loadPdfPage(uri, 0) else {
                pdfPageBitmap = null
                pdfPageCount = 0
                pdfViewerStatus = ""
            }
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


    private val saveHtmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف HTML..."
                try {
                    withContext(Dispatchers.IO) {
                        val escapedText = extractedText
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("\"", "&quot;")
                        val html = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeXml(selectedName)}</title>
<style>
body { font-family: sans-serif; margin: 24px; line-height: 1.8; direction: rtl; }
.document-text { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
</head>
<body><main class="document-text">${escapedText}</main></body>
</html>"""
                        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(html)
                        } ?: error("تعذر فتح ملف الحفظ")
                    }
                    status = "تم تصدير النص إلى HTML. يمكنك فتحه في المتصفح أو مشاركته؛ لا يحتفظ بتنسيق المستند الأصلي."
                } catch (e: Exception) {
                    status = "فشل إنشاء HTML: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally {
                    isBusy = false
                }
            }
        }

    private val saveMarkdownLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف Markdown..."
                try {
                    withContext(Dispatchers.IO) {
                        val markdown = "# ${selectedName.replace("\n", " ").trim()}\n\n" + extractedText
                        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(markdown)
                        } ?: error("تعذر فتح ملف الحفظ")
                    }
                    status = "تم تصدير النص إلى Markdown مع الاحتفاظ بفواصل الأسطر. قد تحتاج إلى تنسيق العناوين والجداول يدويًا."
                } catch (e: Exception) {
                    status = "فشل إنشاء Markdown: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally {
                    isBusy = false
                }
            }
        }

    private val savePdfExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف PDF..."
                try {
                    withContext(Dispatchers.IO) {
                        val document = PdfDocument()
                        val pageWidth = 595
                        val pageHeight = 842
                        val margin = 40f
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 12f
                            color = android.graphics.Color.BLACK
                        }
                        val wrappedLines = extractedText.lines().flatMap { line ->
                            if (line.isEmpty()) listOf("") else line.chunked(78)
                        }
                        var pageNumber = 1
                        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                        var canvas: Canvas = page.canvas
                        var y = margin + paint.textSize
                        for (line in wrappedLines) {
                            if (y > pageHeight - margin) {
                                document.finishPage(page)
                                pageNumber++
                                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                                canvas = page.canvas
                                y = margin + paint.textSize
                            }
                            canvas.drawText(line, pageWidth - margin, y, paint)
                            y += paint.textSize * 1.6f
                        }
                        document.finishPage(page)
                        try {
                            val output = contentResolver.openOutputStream(uri) ?: error("تعذر فتح ملف الحفظ")
                            output.use { document.writeTo(it) }
                        } finally {
                            document.close()
                        }
                    }
                    status = "تم إنشاء ملف PDF. قد يختلف تنسيق النص عن المستند الأصلي."
                } catch (e: Exception) {
                    status = "فشل إنشاء PDF: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally {
                    isBusy = false
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

    private val saveXlsxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) lifecycleScope.launch {
                isBusy = true
                status = "جارٍ إنشاء ملف Excel..."
                try {
                    withContext(Dispatchers.IO) {
                        val rows = extractedText.lineSequence().map { line ->
                            if (line.contains('\t')) line.split('\t') else listOf(line)
                        }.toList()
                        val sheetRows = rows.mapIndexed { rowIndex, cells ->
                            val xmlCells = cells.mapIndexed { colIndex, value ->
                                val ref = excelColumnName(colIndex + 1) + (rowIndex + 1)
                                """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>"""
                            }.joinToString("")
                            """<row r="${rowIndex + 1}">$xmlCells</row>"""
                        }.joinToString("")
                        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheetRows</sheetData></worksheet>"""
                        val output = contentResolver.openOutputStream(uri) ?: error("تعذر فتح ملف الحفظ")
                        ZipOutputStream(output).use { zip ->
                            writeZipText(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                            writeZipText(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                            writeZipText(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="DocShop" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                            writeZipText(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                            writeZipText(zip, "xl/worksheets/sheet1.xml", sheetXml)
                        }
                    }
                    status = "تم إنشاء ملف XLSX. إذا كان المصدر PDF، فقد تحتاج الجداول إلى ترتيب يدوي لأن استخراج النص لا يحدد الأعمدة دائمًا."
                } catch (e: Exception) {
                    status = "فشل إنشاء Excel: ${e.localizedMessage ?: "خطأ غير معروف"}"
                } finally { isBusy = false }
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        loadRecentDocuments()
        draftSavedAt = getSharedPreferences("docshop_text_draft", MODE_PRIVATE).getLong("saved_at", 0L)

        setContent {
            MaterialTheme {
                LaunchedEffect(extractedText, selectedName) {
                    if (extractedText.isNotEmpty() && extractedText.length <= 400000) {
                        delay(800)
                        getSharedPreferences("docshop_text_draft", MODE_PRIVATE).edit()
                            .putString("text", extractedText)
                            .putString("name", selectedName)
                            .putLong("saved_at", System.currentTimeMillis())
                            .putBoolean("auto_saved", true)
                            .apply()
                        draftSavedAt = getSharedPreferences("docshop_text_draft", MODE_PRIVATE).getLong("saved_at", 0L)
                    }
                }
                if (showExcelCellDialog) {
                    AlertDialog(
                        onDismissRequest = { showExcelCellDialog = false },
                        title = { Text("تعديل خلية Excel") },
                        text = {
                            OutlinedTextField(
                                value = excelCellValue,
                                onValueChange = { excelCellValue = it },
                                label = { Text("قيمة الخلية") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                updateExcelCell(editingExcelRow, editingExcelColumn, excelCellValue)
                                showExcelCellDialog = false
                            }) { Text("حفظ التعديل") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExcelCellDialog = false }) { Text("إلغاء") }
                        }
                    )
                }
                if (showPasswordDialog) {
                    AlertDialog(
                        onDismissRequest = { showPasswordDialog = false; pdfPassword = "" },
                        title = { Text("ملف PDF محمي") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("أدخل كلمة مرور ملف PDF الذي تملكه أو لديك إذن بفتحه.")
                                OutlinedTextField(
                                    value = pdfPassword,
                                    onValueChange = { pdfPassword = it },
                                    label = { Text("كلمة المرور") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val bytes = pendingPdfBytes
                                if (bytes != null) loadProtectedPdf(bytes, pdfPassword)
                                showPasswordDialog = false
                            }) { Text("فتح الملف") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPasswordDialog = false; pdfPassword = ""; pendingPdfBytes = null }) {
                                Text("إلغاء")
                            }
                        }
                    )
                }
                if (showDeleteDraftDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDraftDialog = false },
                        title = { Text("حذف المسودة المحفوظة؟") },
                        text = { Text("سيتم حذف المسودة المخزنة محليًا ووقت حفظها فقط. لن يتم حذف النص الموجود حاليًا في مساحة العمل أو أي ملف أصلي من الهاتف.") },
                        confirmButton = {
                            TextButton(onClick = {
                                getSharedPreferences("docshop_text_draft", MODE_PRIVATE).edit()
                                    .remove("text")
                                    .remove("name")
                                    .remove("saved_at")
                                    .remove("auto_saved")
                                    .apply()
                                draftSavedAt = 0L
                                status = "تم حذف المسودة المحفوظة من مساحة التطبيق. المحتوى الحالي والملفات الأصلية لم تتغير."
                                showDeleteDraftDialog = false
                            }) { Text("حذف المسودة") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDraftDialog = false }) { Text("إلغاء") }
                        }
                    )
                }
                if (showClearTextDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearTextDialog = false },
                        title = { Text("مسح المحتوى المستخرج؟") },
                        text = { Text("سيتم مسح النص من مساحة العمل الحالية فقط. لن يتم حذف الملف الأصلي من الهاتف، ولن يمكن التراجع عن هذا المسح.") },
                        confirmButton = {
                            TextButton(onClick = {
                                extractedText = ""
                                previousTextEdit = null
                                redoTextEdit = null
                                previousExcelText = null
                                documentSearchQuery = ""
                                replaceSearchText = ""
                                replaceWithText = ""
                                replaceStatus = ""
                                status = "تم مسح المحتوى المستخرج من مساحة العمل. الملف الأصلي لم يتغير."
                                showClearTextDialog = false
                            }) { Text("مسح المحتوى") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearTextDialog = false }) { Text("إلغاء") }
                        }
                    )
                }
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

                        if (recentDocuments.isNotEmpty()) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("الملفات الأخيرة", style = MaterialTheme.typography.titleMedium)
                                        TextButton(onClick = { clearRecentDocuments() }, enabled = !isBusy) { Text("مسح السجل") }
                                    }
                                    recentDocuments.forEach { item ->
                                        OutlinedButton(onClick = { openRecentDocument(item.first) },
                                            enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Text(item.second, fontWeight = FontWeight.SemiBold)
                                                Text("اضغط لإعادة فتح الملف", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
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

                        if (selectedPdfUri != null) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("عارض PDF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(pdfViewerStatus.ifBlank { "تحميل صفحات PDF..." }, style = MaterialTheme.typography.bodyMedium)
                                    if (pdfPageBitmap != null) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { if (pdfPageIndex > 0) selectedPdfUri?.let { loadPdfPage(it, pdfPageIndex - 1) } },
                                                enabled = !isBusy && pdfPageIndex > 0) { Text("السابق") }
                                            Text("صفحة ${pdfPageIndex + 1} من $pdfPageCount")
                                            TextButton(onClick = { if (pdfPageIndex + 1 < pdfPageCount) selectedPdfUri?.let { loadPdfPage(it, pdfPageIndex + 1) } },
                                                enabled = !isBusy && pdfPageIndex + 1 < pdfPageCount) { Text("التالي") }
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { pdfZoom = (pdfZoom - 0.2f).coerceAtLeast(0.6f) }) { Text("−") }
                                            Text("التكبير: ${(pdfZoom * 100).toInt()}%")
                                            TextButton(onClick = { pdfZoom = (pdfZoom + 0.2f).coerceAtMost(2.5f) }) { Text("+") }
                                            TextButton(onClick = { pdfZoom = 1f }) { Text("إعادة") }
                                        }
                                        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 620.dp)
                                            .verticalScroll(rememberScrollState())) {
                                            Image(bitmap = pdfPageBitmap!!.asImageBitmap(),
                                                contentDescription = "صفحة PDF رقم ${pdfPageIndex + 1}",
                                                contentScale = ContentScale.FillWidth,
                                                modifier = Modifier.fillMaxWidth().graphicsLayer(
                                                    scaleX = pdfZoom, scaleY = pdfZoom
                                                ))
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedName.lowercase().endsWith(".docx")) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("معاينة مستند Word", style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold)
                                    Text("هذه معاينة للنص والفقرات والجداول المستخرجة. لا تُعرض تنسيقات Word الأصلية بالكامل بعد.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary)
                                    if (extractedText.isBlank()) {
                                        Text("لا يوجد نص قابل للمعاينة في المستند.")
                                    } else {
                                        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                                            .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            extractedText.split("\n").forEach { line ->
                                                if (line.isBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                } else if (line.contains('\t')) {
                                                    Card(colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                                        Text(line.split('\t').joinToString("   |   "),
                                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                            style = MaterialTheme.typography.bodyMedium)
                                                    }
                                                } else {
                                                    Text(line, style = MaterialTheme.typography.bodyLarge)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedName.lowercase().endsWith(".xlsx")) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("معاينة جدول Excel", style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold)
                                    Text("اضغط على أي خلية لتعديل قيمتها. التغييرات تطبق على البيانات المستخرجة ويمكن تصديرها كملف جديد؛ لا يتغير الملف الأصلي.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary)
                                    OutlinedButton(
                                        onClick = {
                                            previousExcelText?.let { oldText ->
                                                extractedText = oldText
                                                previousExcelText = null
                                                status = "تم التراجع عن آخر تعديل للجدول."
                                            }
                                        },
                                        enabled = !isBusy && previousExcelText != null,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("التراجع عن آخر تعديل")
                                    }
                                    val rows = extractedText.lineSequence()
                                        .take(300)
                                        .map { it.split('\t') }
                                        .toList()
                                    if (rows.isEmpty()) {
                                        Text("لا توجد بيانات لعرضها في الجدول.")
                                    } else {
                                        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                            .verticalScroll(rememberScrollState())) {
                                            rows.forEachIndexed { rowIndex, cells ->
                                                Row(modifier = Modifier.fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState())) {
                                                    cells.forEachIndexed { columnIndex, cell ->
                                                        Surface(
                                                            modifier = Modifier.widthIn(min = 96.dp, max = 220.dp)
                                                                .clickable {
                                                                    editingExcelRow = rowIndex
                                                                    editingExcelColumn = columnIndex
                                                                    excelCellValue = cell
                                                                    showExcelCellDialog = true
                                                                },
                                                            color = if (rowIndex == 0) MaterialTheme.colorScheme.secondaryContainer
                                                                else if (rowIndex % 2 == 0) MaterialTheme.colorScheme.surfaceVariant
                                                                else MaterialTheme.colorScheme.surface,
                                                            border = androidx.compose.foundation.BorderStroke(0.5.dp,
                                                                MaterialTheme.colorScheme.outlineVariant)
                                                        ) {
                                                            Text(
                                                                text = cell.ifBlank { " " },
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                                                maxLines = 4
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (extractedText.lineSequence().count() > 300) {
                                            Text("تم عرض أول 300 صف فقط لتقليل استهلاك الذاكرة.",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        if (extractedText.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { showClearTextDialog = true },
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("مسح المحتوى المستخرج")
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("المحتوى المستخرج", style = MaterialTheme.typography.titleLarge)
                            Text("${extractedText.length} حرف", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedTextField(
                            value = documentSearchQuery,
                            onValueChange = { documentSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy,
                            singleLine = true,
                            label = { Text("البحث داخل المستند") },
                            placeholder = { Text("اكتب كلمة أو عبارة للبحث") },
                            trailingIcon = {
                                if (documentSearchQuery.isNotEmpty()) {
                                    TextButton(onClick = { documentSearchQuery = "" }) { Text("مسح") }
                                }
                            }
                        )
                        if (documentSearchQuery.isNotBlank()) {
                            val matchCount = Regex(Regex.escape(documentSearchQuery), RegexOption.IGNORE_CASE)
                                .findAll(extractedText).count()
                            Text(
                                if (matchCount == 0) "لا توجد نتائج مطابقة."
                                else "عدد النتائج: $matchCount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("بحث واستبدال النص", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = replaceSearchText,
                                    onValueChange = { replaceSearchText = it; replaceStatus = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isBusy,
                                    singleLine = true,
                                    label = { Text("النص المراد استبداله") }
                                )
                                OutlinedTextField(
                                    value = replaceWithText,
                                    onValueChange = { replaceWithText = it; replaceStatus = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isBusy,
                                    singleLine = true,
                                    label = { Text("النص البديل") }
                                )
                                Button(
                                    onClick = {
                                        if (replaceSearchText.isBlank()) {
                                            replaceStatus = "اكتب النص الذي تريد استبداله أولاً."
                                        } else {
                                            val matches = Regex(Regex.escape(replaceSearchText), RegexOption.IGNORE_CASE)
                                                .findAll(extractedText).count()
                                            if (matches == 0) {
                                                replaceStatus = "لم يتم العثور على النص المطلوب."
                                            } else {
                                                previousTextEdit = extractedText
                                                redoTextEdit = null
                                                extractedText = extractedText.replace(replaceSearchText, replaceWithText, ignoreCase = true)
                                                replaceStatus = "تم استبدال $matches من النتائج."
                                                replaceSearchText = ""
                                            }
                                        }
                                    },
                                    enabled = !isBusy && extractedText.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("استبدال جميع النتائج")
                                }
                                if (replaceStatus.isNotBlank()) {
                                    Text(replaceStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }

                        if (extractedText.isNotEmpty()) {
                            val wordCount = extractedText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                            val lineCount = extractedText.count { it == '\n' } + 1
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("إحصائيات النص", style = MaterialTheme.typography.titleMedium)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("$wordCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                            Text("كلمة", style = MaterialTheme.typography.labelMedium)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${extractedText.length}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                            Text("حرف", style = MaterialTheme.typography.labelMedium)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("$lineCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                            Text("سطر", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
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

                        if (draftSavedAt > 0L) {
                            Text(
                                "آخر حفظ للمسودة: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(draftSavedAt)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (extractedText.length > 400000) {
                                    status = "المسودة كبيرة جدًا للحفظ داخل إعدادات التطبيق. صدّرها كملف TXT بدلًا من ذلك."
                                } else {
                                    val draftPrefs = getSharedPreferences("docshop_text_draft", MODE_PRIVATE)
                                    draftPrefs.edit()
                                        .putString("text", extractedText)
                                        .putString("name", selectedName)
                                        .putLong("saved_at", System.currentTimeMillis())
                                        .putBoolean("auto_saved", false)
                                        .apply()
                                    draftSavedAt = draftPrefs.getLong("saved_at", 0L)
                                    status = "تم حفظ مسودة النص محليًا على الهاتف."
                                }
                            },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حفظ مسودة النص على الهاتف")
                        }

                        OutlinedButton(
                            onClick = {
                                if (extractedText.isNotBlank()) {
                                    status = "لحماية النص الحالي، امسح المحتوى المستخرج أولًا ثم استعد المسودة."
                                } else {
                                    val draftPrefs = getSharedPreferences("docshop_text_draft", MODE_PRIVATE)
                                    val draft = draftPrefs.getString("text", null)
                                    if (draft == null) {
                                        status = "لا توجد مسودة محفوظة على هذا الهاتف."
                                    } else {
                                        extractedText = draft
                                        selectedName = draftPrefs.getString("name", "مسودة محفوظة") ?: "مسودة محفوظة"
                                        previousTextEdit = null
                                        redoTextEdit = null
                                        status = "تمت استعادة المسودة المحفوظة محليًا."
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("استعادة المسودة المحفوظة")
                        }

                        OutlinedButton(
                            onClick = { showDeleteDraftDialog = true },
                            enabled = !isBusy && draftSavedAt > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حذف المسودة المحفوظة")
                        }

                        OutlinedButton(onClick = { savePdfExportLauncher.launch("DocShop-export.pdf") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير النص إلى PDF")
                        }

                        OutlinedButton(onClick = { saveHtmlLauncher.launch("DocShop-export.html") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير النص إلى HTML")
                        }

                        OutlinedButton(onClick = { saveMarkdownLauncher.launch("DocShop-export.md") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير النص إلى Markdown")
                        }


                        OutlinedButton(onClick = { saveDocxLauncher.launch("DocShop-export.docx") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير النص إلى Word (DOCX)")
                        }
                        OutlinedButton(onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, selectedName)
                                putExtra(Intent.EXTRA_TEXT, extractedText)
                            }
                            startActivity(Intent.createChooser(shareIntent, "مشاركة النص المستخرج"))
                        }, enabled = !isBusy && extractedText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("مشاركة النص المستخرج")
                        }

                        OutlinedButton(onClick = {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(selectedName, extractedText))
                            Toast.makeText(this@MainActivity, "تم نسخ النص إلى الحافظة", Toast.LENGTH_SHORT).show()
                        }, enabled = !isBusy && extractedText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("نسخ النص إلى الحافظة")
                        }

                        OutlinedButton(onClick = { saveCsvLauncher.launch("DocShop-export.csv") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير البيانات إلى CSV")
                        }
                        OutlinedButton(onClick = { saveXlsxLauncher.launch("DocShop-export.xlsx") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير البيانات إلى Excel (XLSX)")
                        }

                        OutlinedButton(
                            onClick = {
                                val cleaned = extractedText.replace(Regex("\\n{3,}"), "\n\n")
                                val removed = extractedText.length - cleaned.length
                                if (removed > 0) { previousTextEdit = extractedText; redoTextEdit = null }
                                extractedText = cleaned
                                status = if (removed > 0) {
                                    "تم تقليل الأسطر الفارغة المتكررة وإزالة $removed محرفًا من الفراغات."
                                } else {
                                    "لا توجد أسطر فارغة متكررة تحتاج إلى تنظيف."
                                }
                            },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("تنظيف الأسطر الفارغة المتكررة")
                        }

                        OutlinedButton(onClick = {
                            val normalized = normalizeTableSpacing(extractedText)
                            if (normalized != extractedText) { previousTextEdit = extractedText; redoTextEdit = null }
                            extractedText = normalized
                            status = "تم تحويل المسافات المتكررة إلى فواصل أعمدة. راجع الجدول قبل التصدير."
                        }, enabled = !isBusy && extractedText.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("تنظيم أعمدة الجداول قبل التصدير")
                        }

                        OutlinedButton(onClick = {
                            val organized = extractPdfTableRows(extractedText)
                            if (organized != extractedText) { previousTextEdit = extractedText; redoTextEdit = null }
                            extractedText = organized
                            status = "تم تنظيم أسطر الجدول إلى أعمدة مع الحفاظ على فواصل الأسطر الفعلية. راجع النتيجة قبل التصدير."
                        }, enabled = !isBusy && extractedText.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("تحسين تقسيم جداول PDF")
                        }

                        OutlinedButton(onClick = {
                            val pdfUri = selectedPdfUri
                            if (pdfUri != null) runArabicOcr(pdfUri)
                        }, enabled = !isBusy && selectedPdfUri != null, modifier = Modifier.fillMaxWidth()) {
                            Text("استخراج النص العربي من PDF المصوّر (OCR)")
                        }

                        OutlinedButton(
                            onClick = {
                                val previous = previousTextEdit
                                if (previous != null) {
                                    redoTextEdit = extractedText
                                    previousTextEdit = null
                                    extractedText = previous
                                    status = "تم التراجع عن آخر تعديل للنص. يمكنك إعادة تطبيقه من زر الإعادة."
                                }
                            },
                            enabled = !isBusy && previousTextEdit != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("التراجع عن آخر تعديل للنص")
                        }

                        OutlinedButton(
                            onClick = {
                                val redo = redoTextEdit
                                if (redo != null) {
                                    previousTextEdit = extractedText
                                    redoTextEdit = null
                                    extractedText = redo
                                    status = "تمت إعادة تطبيق آخر تعديل للنص."
                                }
                            },
                            enabled = !isBusy && redoTextEdit != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("إعادة تطبيق آخر تعديل للنص")
                        }

                        OutlinedButton(onClick = {
                            selectedName = "لم يتم اختيار ملف"
                            extractedText = ""
                            status = "تم إفراغ مساحة التحرير."
                        }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("إفراغ مساحة التحرير")
                        }

                        Text(
                            "تنبيه: OCR يستخرج النص من صفحات PDF المصوّرة ولا يحافظ على تخطيط الصفحة أو الجداول بدقة مضمونة. ملفات DOC القديمة وXLS القديمة وAccess غير مدعومة بعد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun runArabicOcr(uri: Uri) {
        lifecycleScope.launch {
            isBusy = true
            status = "جارٍ تجهيز OCR العربي..."
            try {
                val text = withContext(Dispatchers.IO) { extractArabicOcr(uri) }
                extractedText = text
                status = if (text.isBlank()) {
                    "اكتمل OCR لكن لم يتم التعرف على نص واضح. جرّب PDF أوضح."
                } else {
                    "اكتمل OCR العربي. راجع النص، خصوصًا الأرقام والتشكيل والجداول، قبل التصدير."
                }
            } catch (e: Exception) {
                status = "تعذر تنفيذ OCR: ${e.localizedMessage ?: "تحقق من أن ملف اللغة العربية مضمن في الإصدار."}"
            } finally {
                isBusy = false
            }
        }
    }

    private fun extractArabicOcr(uri: Uri): String {
        val pdfFile = File(cacheDir, "docshop-ocr-input.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
        } ?: error("تعذر قراءة ملف PDF")
        val tessDataDir = File(filesDir, "tessdata")
        if (!tessDataDir.exists()) tessDataDir.mkdirs()
        val trainedData = File(tessDataDir, "ara.traineddata")
        if (!trainedData.exists()) {
            assets.open("tessdata/ara.traineddata").use { input ->
                FileOutputStream(trainedData).use { output -> input.copyTo(output) }
            }
        }

        val api = TessBaseAPI()
        if (!api.init(filesDir.absolutePath, "ara")) {
            api.end()
            error("تعذر تهيئة محرك OCR العربي")
        }
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = mutableListOf<String>()
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val targetWidth = 1600
                            val scale = targetWidth.toFloat() / page.width.toFloat()
                            val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                api.setImage(bitmap)
                                pages.add(api.getUTF8Text().orEmpty().trim())
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    return pages.filter { it.isNotBlank() }.joinToString("\n\n")
                }
            }
        } finally {
            api.end()
            pdfFile.delete()
        }
    }

    private fun extractPdfText(bytes: ByteArray, password: String): String {
        PDDocument.load(ByteArrayInputStream(bytes), password).use { document ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            return stripper.getText(document)
        }
    }

    private fun loadProtectedPdf(bytes: ByteArray, password: String) {
        lifecycleScope.launch {
            isBusy = true
            status = "جارٍ فتح ملف PDF..."
            try {
                val text = withContext(Dispatchers.IO) { extractPdfText(bytes, password) }
                extractedText = text
                status = if (text.isBlank()) "تم فتح الملف لكن لم يُعثر على نص قابل للاستخراج." else "تم فتح ملف PDF بنجاح."
                pendingPdfBytes = null
                pdfPassword = ""
            } catch (e: Exception) {
                status = "تعذر فتح PDF. تحقق من كلمة المرور أو سلامة الملف."
            } finally {
                isBusy = false
            }
        }
    }

    private fun extractPdfTableRows(text: String): String {
        return text.lineSequence().map { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) "" else line.replace(Regex("\\s{2,}"), "\\t")
        }.joinToString("\n")
    }
    private fun updateExcelCell(rowIndex: Int, columnIndex: Int, value: String) {
        previousExcelText = extractedText
        val rows = extractedText.split('\n').toMutableList()
        while (rows.size <= rowIndex) rows.add("")
        val cells = rows[rowIndex].split('\t').toMutableList()
        while (cells.size <= columnIndex) cells.add("")
        cells[columnIndex] = value
        rows[rowIndex] = cells.joinToString("\t")
        extractedText = rows.joinToString("\n")
        status = "تم تعديل الخلية في المحتوى المستخرج. صدّر الملف لحفظ نسخة جديدة."
    }
    private fun normalizeTableSpacing(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            line.trim().replace(Regex(" {2,}"), "\t")
        }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun excelColumnName(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            n = (n - 1) / 26
        }
        return result.toString()
    }
    private fun writeZipText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun loadPdfPage(uri: Uri, pageIndex: Int) {
        lifecycleScope.launch {
            pdfViewerStatus = "جارٍ تحميل الصفحة..."
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "docshop-viewer.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    } ?: error("تعذر قراءة ملف PDF")
                    try {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                if (renderer.pageCount == 0) error("ملف PDF لا يحتوي على صفحات")
                                val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                                renderer.openPage(safeIndex).use { page ->
                                    val width = 1400
                                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    Triple(bitmap, renderer.pageCount, safeIndex)
                                }
                            }
                        }
                    } finally {
                        file.delete()
                    }
                }
                pdfPageBitmap = result.first
                pdfPageCount = result.second
                pdfPageIndex = result.third
                pdfZoom = 1f
                pdfViewerStatus = "تم تحميل صفحة PDF."
            } catch (e: Exception) {
                pdfPageBitmap = null
                pdfViewerStatus = "تعذر عرض الصفحات. قد يكون الملف محميًا أو غير صالح للعرض."
            }
        }
    }

    private fun rememberRecentDocument(uri: Uri, name: String) {
        val key = uri.toString()
        recentDocuments.removeAll { it.first == key }
        recentDocuments.add(0, key to name)
        while (recentDocuments.size > 8) recentDocuments.removeAt(recentDocuments.lastIndex)
        val prefs = getSharedPreferences("docshop_recent_documents", MODE_PRIVATE)
        val editor = prefs.edit().putInt("count", recentDocuments.size)
        recentDocuments.forEachIndexed { index, item ->
            editor.putString("uri_$index", item.first)
            editor.putString("name_$index", item.second)
        }
        for (index in recentDocuments.size..7) {
            editor.remove("uri_$index")
            editor.remove("name_$index")
        }
        editor.apply()
    }

    private fun loadRecentDocuments() {
        val prefs = getSharedPreferences("docshop_recent_documents", MODE_PRIVATE)
        val count = prefs.getInt("count", 0).coerceIn(0, 8)
        recentDocuments.clear()
        for (index in 0 until count) {
            val uri = prefs.getString("uri_$index", null)
            val name = prefs.getString("name_$index", null)
            if (uri != null && name != null) recentDocuments.add(uri to name)
        }
    }

    private fun clearRecentDocuments() {
        recentDocuments.clear()
        getSharedPreferences("docshop_recent_documents", MODE_PRIVATE).edit().clear().apply()
        status = "تم مسح سجل الملفات الأخيرة. لم يتم حذف الملفات من الهاتف."
    }

    private fun openRecentDocument(uriText: String) {
        val uri = Uri.parse(uriText)
        selectedName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "document"
        selectedPdfUri = if (selectedName.lowercase().endsWith(".pdf") ||
            contentResolver.getType(uri).equals("application/pdf", true)) uri else null
        readDocument(uri)
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
                                val bytes = input.readBytes()
                                try {
                                    extractPdfText(bytes, "")
                                } catch (e: Exception) {
                                    pendingPdfBytes = bytes
                                    showPasswordDialog = true
                                    throw IllegalStateException("قد يكون ملف PDF محميًا بكلمة مرور. أدخل كلمة المرور للمتابعة.", e)
                                }
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
                previousTextEdit = null
                redoTextEdit = null
                status = if (result.isBlank()) {
                    "لم يُعثر على نص. قد يكون الملف فارغًا أو يحتوي على صور فقط."
                } else {
                    "تم استخراج النص. راجعه قبل حفظ نسخة TXT."
                }
            } catch (e: Exception) {
                status = e.localizedMessage ?: "تعذرت قراءة الملف."
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
        var inTable = false
        var inCell = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tbl" -> inTable = true
                    "tc" -> inCell = true
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "tc" -> {
                        inCell = false
                        output.append("\t")
                    }
                    "tr" -> {
                        if (output.endsWith("\t")) output.setLength(output.length - 1)
                        output.append("\n")
                    }
                    "p" -> if (!inTable || !inCell) output.append("\n") else output.append(" ")
                    "tbl" -> {
                        inTable = false
                        if (output.isNotEmpty() && !output.endsWith("\n")) output.append("\n")
                    }
                }
                XmlPullParser.TEXT -> output.append(parser.text)
            }
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        return output.toString().replace(Regex("[ \\t]+\\n"), "\n").trimEnd()
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
