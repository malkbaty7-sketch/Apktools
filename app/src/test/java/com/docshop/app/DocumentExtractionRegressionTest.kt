package com.docshop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentExtractionRegressionTest {
    @Test
    fun decodesUtf8AndUtf16BomText() {
        val text = "اللغة العربية"
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xFF.toByte(),0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xFE.toByte(),0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun readsDocxTablesAndFootnotes() {
        val bytes = zip(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>فقرة</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>خلية أ</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>خلية ب</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""",
            "word/footnotes.xml" to """<w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:footnote w:id="1"><w:p><w:r><w:t>هامش</w:t></w:r></w:p></w:footnote></w:footnotes>"""
        )
        val text = OfficeDocumentReader.readDocx(bytes)
        assertTrue(text.contains("فقرة"))
        assertTrue(text.contains("خلية أ"))
        assertTrue(text.contains("خلية ب"))
        assertTrue(text.contains("الحواشي السفلية"))
        assertTrue(text.contains("هامش"))
    }

    @Test
    fun readsExcelBooleansCachedFormulasAndDateCells() {
        val bytes = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="بيانات" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/styles.xml" to """<styleSheet><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="b"><v>1</v></c><c r="B1"><f>1+1</f><v>2</v></c><c r="C1" s="1"><v>45292</v></c></row></sheetData></worksheet>"""
        )
        val text = OfficeDocumentReader.readXlsx(bytes)
        assertTrue(text.contains("TRUE"))
        assertTrue(text.contains("2"))
        assertTrue(text.contains("2024-01-01"))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
