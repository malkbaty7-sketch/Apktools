package com.docshop.app

import com.healthmarketscience.jackcess.DatabaseBuilder
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.extractor.ExcelExtractor
import java.io.File
import java.io.InputStream

object LegacyDocumentReader {
    fun readDoc(input: InputStream): String = HWPFDocument(input).use { doc ->
        WordExtractor(doc).use { it.text }
    }
    fun readXls(input: InputStream): String = HSSFWorkbook(input).use { workbook ->
        ExcelExtractor(workbook).use { extractor ->
            extractor.setFormulasNotResults(false)
            extractor.text
        }
    }
    fun readAccess(input: InputStream, cacheDir: File): String {
        val temp = File(cacheDir, "docshop-access-" + System.currentTimeMillis() + ".db")
        try {
            temp.outputStream().use { output -> input.copyTo(output) }
            return DatabaseBuilder.open(temp).use { database ->
                buildString {
                    database.tableNames.sorted().forEach { tableName ->
                        append("【").append(tableName).append("】\n")
                        database.getTable(tableName).use { table ->
                            table.forEach { row ->
                                append(row.entries.joinToString("\t") { entry ->
                                    entry.key + "=" + (entry.value?.toString() ?: "")
                                }).append("\n")
                            }
                        }
                        append("\n")
                    }
                }
            }
        } finally { temp.delete() }
    }
}