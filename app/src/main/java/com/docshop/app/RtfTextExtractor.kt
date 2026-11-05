package com.docshop.app

import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * Small RTF text reader for common Word-generated RTF documents.
 * Supports Unicode control words, fallback-character skipping and Windows code pages.
 * This intentionally does not attempt to render RTF layout, fields or embedded objects.
 */
object RtfTextExtractor {
    private data class State(
        var skip: Boolean = false,
        var codePage: Int = 1252,
        var unicodeFallback: Int = 1
    )

    private val ignoredDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "filetbl", "listtable", "listoverridetable", "revtbl", "rsidtbl",
        "xmlnstbl", "generator", "datastore", "themedata", "colorschememapping"
    )

    fun extract(rtf: String): String {
        require(rtf.trimStart().startsWith("{\\rtf")) { "ملف RTF غير صالح" }
        val out = StringBuilder()
        val state = State()
        val stack = ArrayDeque<State>()
        var i = 0
        var skipFallback = 0

        fun appendByte(value: Int) {
            if (state.skip) return
            val charset = try {
                Charset.forName("windows-${state.codePage}")
            } catch (_: Exception) {
                Charsets.ISO_8859_1
            }
            val decoded = charset.newDecoder().decode(ByteBuffer.wrap(byteArrayOf(value.toByte()))).toString()
            out.append(decoded)
        }

        while (i < rtf.length) {
            val ch = rtf[i]
            if (skipFallback > 0) {
                if (ch == '\\' && i + 3 < rtf.length && rtf[i + 1] == '\'') {
                    i += 4
                    skipFallback--
                } else if (ch == '\\' && i + 1 < rtf.length && rtf[i + 1] in charArrayOf('\\', '{', '}')) {
                    i += 2
                    skipFallback--
                } else if (ch != '{' && ch != '}') {
                    i++
                    if (ch != '\r' && ch != '\n') skipFallback--
                } else {
                    i++
                }
                continue
            }

            when {
                ch == '{' -> {
                    stack.addLast(state.copy())
                    i++
                }
                ch == '}' -> {
                    if (stack.isNotEmpty()) {
                        val restored = stack.removeLast()
                        state.skip = restored.skip
                        state.codePage = restored.codePage
                        state.unicodeFallback = restored.unicodeFallback
                    }
                    i++
                }
                ch == '\\' && i + 1 < rtf.length -> {
                    val next = rtf[i + 1]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!state.skip) out.append(next)
                            i += 2
                        }
                        next == '*' -> {
                            state.skip = true
                            i += 2
                        }
                        next == '\'' && i + 3 < rtf.length -> {
                            val byte = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            if (byte != null) {
                                if (skipFallback > 0) skipFallback-- else appendByte(byte)
                            }
                            i += 4
                        }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                                if (rtf[k] == '-') k++
                                while (k < rtf.length && rtf[k].isDigit()) k++
                            }
                            val parameter = rtf.substring(j, k).toIntOrNull()
                            if (word in ignoredDestinations) state.skip = true
                            if (!state.skip) when (word) {
                                "ansicpg" -> parameter?.let { state.codePage = it }
                                "uc" -> parameter?.let { state.unicodeFallback = it.coerceIn(0, 8) }
                                "u" -> parameter?.let { out.append(it.toShort().toInt().toChar()); skipFallback = state.unicodeFallback }
                                "par", "line" -> out.append('\n')
                                "tab" -> out.append('\t')
                                "page" -> out.append('\n')
                                "emdash" -> out.append('—')
                                "endash" -> out.append('–')
                                "bullet" -> out.append('•')
                                "lquote" -> out.append('‘')
                                "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“')
                                "rdblquote" -> out.append('”')
                                "~" -> out.append('\u00A0')
                            }
                            i = if (k < rtf.length && rtf[k] == ' ') k + 1 else k
                        }
                        next == '-' -> { if (!state.skip) out.append('­'); i += 2 }
                        next == '_' -> { if (!state.skip) out.append('‑'); i += 2 }
                        next == '~' -> { if (!state.skip) out.append('\u00A0'); i += 2 }
                        next == '\n' || next == '\r' -> i += 2
                        else -> i += 2
                    }
                }
                ch == '\r' || ch == '\n' -> i++
                !state.skip && ch.code in 0x80..0xFF -> { appendByte(ch.code); i++ }
                !state.skip -> { out.append(ch); i++ }
                else -> i++
            }
        }
        return out.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
