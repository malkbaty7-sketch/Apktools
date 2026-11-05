package com.docshop.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextFileDecoderTest {
    @Test
    fun decodesUtf8AndUtf16BomText() {
        val text = "اللغة العربية"
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, TextFileDecoder.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun decodesLegacyWindowsArabicText() {
        val text = "مرحبا"
        val encoded = Charset.forName("windows-1256").encode(text)
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        assertEquals(text, TextFileDecoder.decode(bytes))
    }
}
