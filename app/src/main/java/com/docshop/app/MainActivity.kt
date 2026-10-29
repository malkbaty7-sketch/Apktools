package com.docshop.app

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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

class MainActivity : ComponentActivity() {
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedName by mutableStateOf("لم يتم اختيار ملف")
    private var extractedText by mutableStateOf("")
    private var status by mutableStateOf("اختر ملف PDF أو TXT للبدء.")
    private var isBusy by mutableStateOf(false)

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers do not offer persistable URI permissions.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "DocShop",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "إدارة ملفاتك النصية ومستندات PDF",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("ابدأ بفتح مستند", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "يمكنك استخراج النص من ملفات PDF التي تحتوي على نص قابل للنسخ، أو فتح ملفات TXT وتعديل محتواها.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = {
                                        openFile.launch(arrayOf("application/pdf", "text/plain"))
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("اختيار ملف من الهاتف")
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("الملف الحالي", style = MaterialTheme.typography.titleMedium)
                                Text(selectedName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    status,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (isBusy) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("النص", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${extractedText.length} حرف",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        OutlinedTextField(
                            value = extractedText,
                            onValueChange = { extractedText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp),
                            enabled = !isBusy,
                            label = { Text("يمكنك تعديل النص هنا") },
                            placeholder = { Text("سيظهر محتوى الملف هنا بعد فتحه") },
                            minLines = 8
                        )

                        Button(
                            onClick = { saveTextLauncher.launch("DocShop-export.txt") },
                            enabled = !isBusy && extractedText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حفظ النص المعدل كملف TXT")
                        }

                        OutlinedButton(
                            onClick = {
                                selectedUri = null
                                selectedName = "لم يتم اختيار ملف"
                                extractedText = ""
                                status = "تم إفراغ مساحة التحرير."
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("إفراغ مساحة التحرير")
                        }

                        Text(
                            "ملاحظة: استخراج النص من PDF لا يحافظ بالضرورة على التخطيط أو الجداول. ملفات PDF المصورة قد تحتاج إلى OCR، وهذه الميزة غير مفعّلة بعد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
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
                    val isPdf = type.equals("application/pdf", ignoreCase = true) ||
                        name.endsWith(".pdf", ignoreCase = true) ||
                        uri.lastPathSegment.orEmpty().endsWith(".pdf", ignoreCase = true)

                    if (isPdf) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            PDDocument.load(input).use { document ->
                                PDFTextStripper().getText(document)
                            }
                        } ?: error("تعذر فتح ملف PDF")
                    } else {
                        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                            it.readText()
                        } ?: error("تعذر فتح الملف النصي")
                    }
                }
                extractedText = result
                status = if (result.isBlank()) {
                    "لم يُعثر على نص قابل للاستخراج. قد يكون PDF مصورًا أو فارغًا."
                } else {
                    "تمت القراءة بنجاح. راجع النص قبل حفظ النسخة المعدلة."
                }
            } catch (e: Exception) {
                status = "تعذرت قراءة الملف: ${e.localizedMessage ?: "قد يكون الملف غير مدعوم أو محميًا"}"
            } finally {
                isBusy = false
            }
        }
    }
}
