package com.docshop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("لم يتم اختيار ملف")
    private var extractedText by mutableStateOf("اختر ملف TXT أو PDF لعرض النص المدعوم.")
    private var status by mutableStateOf("جاهز")
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedName = uri.lastPathSegment ?: "document"
            readDocument(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("DocShop", style = MaterialTheme.typography.headlineLarge)
                        Text("عارض المستندات وأداة استخراج النصوص")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { openFile.launch(arrayOf("application/pdf", "text/plain", "text/*")) }) {
                            Text("اختيار ملف")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(selectedName, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = extractedText,
                            onValueChange = { extractedText = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = { Text("النص المستخرج / القابل للتحرير") }
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { saveText() }, modifier = Modifier.fillMaxWidth()) {
                            Text("حفظ النص كملف TXT")
                        }
                    }
                }
            }
        }
    }

    private fun readDocument(uri: Uri) {
        status = "جارٍ قراءة الملف..."
        try {
            val type = contentResolver.getType(uri).orEmpty()
            if (type == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", true)) {
                contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input).use { doc ->
                        extractedText = PDFTextStripper().getText(doc)
                    }
                }
            } else {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    extractedText = it.readText()
                }
            }
            status = "تم استخراج النص المتاح. قد لا يحافظ على تنسيق الملف الأصلي."
        } catch (e: Exception) {
            status = "تعذرت القراءة: ${e.localizedMessage ?: "خطأ غير معروف"}"
        }
    }

    private fun saveText() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "DocShop-export.txt")
        }
        saveTextLauncher.launch(intent)
    }

    private val saveTextLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(extractedText) }
                status = "تم حفظ ملف TXT."
            } catch (e: Exception) {
                status = "فشل الحفظ: ${e.localizedMessage ?: "خطأ غير معروف"}"
            }
        }
    }
}
