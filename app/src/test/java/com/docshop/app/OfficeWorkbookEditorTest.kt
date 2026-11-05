package com.docshop.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OfficeWorkbookEditorTest {
    @Test
    fun updatesMultipleSheetsAndKeepsUnchangedFormula() {
        val original = fixtureWorkbook()
        val edited = """
            【Summary】
            عنوان\t7
            5

            【Data】
            الاسم\tالقيمة
            علي\t11
        """.trimIndent().replace("\\t", "\t")

        val result = OfficeWorkbookEditor.updateXlsx(original, edited)
        val entries = unzip(result)
        val summary = entries.getValue("xl/worksheets/sheet1.xml")
        val data = entries.getValue("xl/worksheets/sheet2.xml")
        val workbook = entries.getValue("xl/workbook.xml")

        assertTrue(summary.contains("<v>7</v>"))
        assertTrue(summary.contains("<f>SUM(B1:B1)</f>"))
        assertTrue(data.contains("11"))
        assertTrue(workbook.contains("fullCalcOnLoad=\"1\""))
    }

    @Test
    fun writesFormulaWhenCellStartsWithEquals() {
        val original = fixtureWorkbook()
        val edited = """
            【Summary】
            عنوان\t5
            =SUM(B1:B1)

            【Data】
            الاسم\tالقيمة
            علي\t10
        """.trimIndent().replace("\\t", "\t")

        val result = OfficeWorkbookEditor.updateXlsx(original, edited)
        val sheet = unzip(result).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<f>SUM(B1:B1)</f>"))
    }

    @Test
    fun editsDocxParagraphsAndTableCellsWithoutDroppingPackageParts() {
        val original = docxFixture()
        val result = OfficeDocumentEditor.updateDocx(original, "فقرة جديدة\nخلية 1\tخلية 2")
        val entries = unzip(result)
        val document = entries.getValue("word/document.xml")
        assertTrue(document.contains("فقرة جديدة"))
        assertTrue(document.contains("خلية 1"))
        assertTrue(document.contains("خلية 2"))
        assertTrue(document.contains("<w:b/>"))
        assertTrue(entries.containsKey("word/styles.xml"))
    }

    private fun docxFixture(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.add("word/document.xml", """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:b/></w:pPr><w:r><w:t>قديم</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>أ</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>ب</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:sectPr/></w:body></w:document>""")
            zip.add("word/styles.xml", "<w:styles/>")
        }
        return output.toByteArray()
    }

    private fun fixtureWorkbook(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.add("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Summary" r:id="rId1"/><sheet name="Data" r:id="rId2"/></sheets></workbook>""")
            zip.add("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""")
            zip.add("xl/worksheets/sheet1.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>عنوان</t></is></c><c r="B1"><v>5</v></c></row><row r="2"><c r="A2"><f>SUM(B1:B1)</f><v>5</v></c></row></sheetData></worksheet>""")
            zip.add("xl/worksheets/sheet2.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>الاسم</t></is></c><c r="B1" t="inlineStr"><is><t>القيمة</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>علي</t></is></c><c r="B2"><v>10</v></c></row></sheetData></worksheet>""")
            zip.add("xl/styles.xml", "<styleSheet/>")
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }
}
