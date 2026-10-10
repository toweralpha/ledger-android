package com.xiaozhangben.ledger.domain.bill

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** One physical row of a bill file, with the line number kept for error reporting. */
data class CsvRow(val lineNumber: Int, val cells: List<String>)

/**
 * A small RFC 4180-ish CSV reader.
 *
 * `String.split(",")` is not usable here: Alipay and WeChat both emit product
 * descriptions that contain commas, and WeChat descriptions occasionally contain
 * newlines. Splitting naively shifts every following column, which would silently
 * turn a 32.00 payment into whatever number happened to land in the amount column.
 */
object CsvParser {

    fun parse(text: String): List<CsvRow> {
        val rows = ArrayList<CsvRow>(2048)
        var cells = ArrayList<String>(16)
        val field = StringBuilder(64)
        var inQuotes = false
        var line = 1
        var rowStartLine = 1
        var i = 0

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i += 2
                    }
                    c == '"' -> {
                        inQuotes = false; i++
                    }
                    else -> {
                        if (c == '\n') line++
                        field.append(c); i++
                    }
                }
                continue
            }
            when (c) {
                '"' -> {
                    inQuotes = true; i++
                }
                ',' -> {
                    cells.add(field.toString()); field.setLength(0); i++
                }
                '\r' -> i++
                '\n' -> {
                    cells.add(field.toString()); field.setLength(0)
                    rows.add(CsvRow(rowStartLine, cells))
                    cells = ArrayList(16)
                    line++; rowStartLine = line; i++
                }
                else -> {
                    field.append(c); i++
                }
            }
        }
        if (field.isNotEmpty() || cells.isNotEmpty()) {
            cells.add(field.toString())
            rows.add(CsvRow(rowStartLine, cells))
        }
        return rows
    }
}

/**
 * Bill exports are not uniformly encoded, and getting this wrong is catastrophic
 * rather than cosmetic: a GBK Alipay file misread as UTF-8 turns every Chinese
 * payee into mojibake, so no keyword rule matches and the entire import lands in
 * the triage queue.
 *
 * Detection order:
 *   1. explicit UTF-8 BOM      -> WeChat exports
 *   2. strict UTF-8 decode     -> succeeds only for real UTF-8 / ASCII
 *   3. GB18030 fallback        -> Alipay exports (superset of GBK)
 */
object CharsetDetector {

    data class Decoded(val text: String, val encoding: String)

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            return Decoded(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), "UTF-8 (BOM)")
        }
        strictUtf8(bytes)?.let { return Decoded(it, "UTF-8") }
        return Decoded(String(bytes, Charset.forName("GB18030")), "GB18030")
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Exception) {
        null
    }
}
