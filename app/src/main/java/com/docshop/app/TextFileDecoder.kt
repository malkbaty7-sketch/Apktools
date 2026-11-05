package com.docshop.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Detects common Unicode BOMs and safely falls back for legacy Arabic Windows text files. */
object TextFileDecoder {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        fun utf(charset: Charset, offset: Int): String =
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

        return when {
            starts(bytes, 0x00, 0x00, 0xFE, 0xFF) -> utf(Charset.forName("UTF-32BE"), 4)
            starts(bytes, 0xFF, 0xFE, 0x00, 0x00) -> utf(Charset.forName("UTF-32LE"), 4)
            starts(bytes, 0xEF, 0xBB, 0xBF) -> utf(Charsets.UTF_8, 3)
            starts(bytes, 0xFE, 0xFF) -> utf(Charsets.UTF_16BE, 2)
            starts(bytes, 0xFF, 0xFE) -> utf(Charsets.UTF_16LE, 2)
            else -> decodeWithoutBom(bytes)
        }
    }

    private fun decodeWithoutBom(bytes: ByteArray): String {
        val evenNulls = bytes.indices.count { it % 2 == 0 && bytes[it] == 0.toByte() }
        val oddNulls = bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() }
        if (oddNulls > bytes.size / 8) return Charset.forName("UTF-16LE").decode(ByteBuffer.wrap(bytes)).toString()
        if (evenNulls > bytes.size / 8) return Charset.forName("UTF-16BE").decode(ByteBuffer.wrap(bytes)).toString()
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // Common Excel CSV exports in Arabic Windows locales use Windows-1256.
            Charset.forName("windows-1256").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun starts(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
}
