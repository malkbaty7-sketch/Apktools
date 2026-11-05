package com.docshop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtfTextExtractorTest {
    @Test
    fun readsUnicodeAndSkipsFallbackCharacter() {
        val text = RtfTextExtractor.extract("""{\rtf1\ansi\uc1 \u1587?\u1604?\u1575?\u1605?}""")
        assertEquals("سلام", text)
    }

    @Test
    fun readsParagraphsAndDoesNotDropHeaders() {
        val text = RtfTextExtractor.extract("""{\rtf1\ansi {\header Header text}Body\par Next}""")
        assertTrue(text.contains("Header text"))
        assertTrue(text.contains("Body"))
        assertTrue(text.contains("Next"))
    }
}
