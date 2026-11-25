package com.phy4l.tcfy.glossary

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Imports and exports domain glossary packs in CSV, TXT, and JSON formats.
 * Automatically handles UTF-8 (with or without BOM) and GBK (common from Excel on Windows in China).
 */
object GlossaryImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun decodeBytesSmart(bytes: ByteArray): String {
        val cleanBytes = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(cleanBytes)).toString()
        } catch (_: Exception) {
            runCatching {
                String(cleanBytes, Charset.forName("GBK"))
            }.getOrElse {
                String(cleanBytes, Charsets.UTF_8)
            }
        }
    }

    fun parseByFileName(fileName: String, content: String, defaultPackName: String = "导入术语库"): GlossaryPackExport {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".json") -> parseJson(content)
            lower.endsWith(".csv") -> GlossaryPackExport(
                name = defaultPackName,
                description = "从 CSV 导入",
                terms = parseCsv(content)
            )
            else -> GlossaryPackExport(
                name = defaultPackName,
                description = "从 TXT 导入",
                terms = parseTxt(content)
            )
        }
    }

    fun parseJson(content: String): GlossaryPackExport {
        return json.decodeFromString<GlossaryPackExport>(content)
    }

    fun exportJson(pack: GlossaryPackExport): String {
        return json.encodeToString(pack)
    }

    /**
     * Parses RFC-4180 CSV with columns:
     * term,translation,aliases,notes
     * Aliases within column 3 can be separated by ';', '|', '、', or '/'.
     */
    fun parseCsv(content: String): List<GlossaryTerm> {
        val rows = parseCsvRows(content)
        val out = mutableListOf<GlossaryTerm>()
        for ((index, row) in rows.withIndex()) {
            if (row.isEmpty() || row.all { it.isBlank() }) continue
            val first = row[0].trim()
            if (first.isEmpty() || first.startsWith("#")) continue
            // Skip header row
            if (index == 0 && first.lowercase() in setOf("term", "canonicalterm", "术语", "原词", "名词")) {
                continue
            }
            val translation = row.getOrNull(1)?.trim().orEmpty()
            val aliasesRaw = row.getOrNull(2)?.trim().orEmpty()
            val notes = row.getOrNull(3)?.trim().orEmpty()
            val aliases = splitAliases(aliasesRaw)
            out.add(
                GlossaryTerm(
                    canonicalTerm = first,
                    translation = translation,
                    aliases = aliases,
                    notes = notes
                )
            )
        }
        return out
    }

    fun exportCsv(terms: List<GlossaryTerm>): String {
        val sb = StringBuilder()
        sb.appendLine("term,translation,aliases,notes")
        for (t in terms) {
            sb.append(csvEscape(t.canonicalTerm)).append(',')
            sb.append(csvEscape(t.translation)).append(',')
            sb.append(csvEscape(t.aliases.joinToString("; "))).append(',')
            sb.appendLine(csvEscape(t.notes))
        }
        return sb.toString()
    }

    /**
     * Parses flexible TXT glossary lines:
     * - `Hamiltonian = 哈密顿量 | 哈密顿良, 哈米顿`
     * - `Eigenvalue -> 特征值`
     * - `Schrödinger\t薛定谔\t学定额;薛定额`
     * - `Wavefunction` (single term per line)
     */
    fun parseTxt(content: String): List<GlossaryTerm> {
        val out = mutableListOf<GlossaryTerm>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            if ('\t' in line) {
                val cols = line.split('\t')
                val term = cols[0].trim()
                if (term.isNotEmpty()) {
                    out.add(
                        GlossaryTerm(
                            canonicalTerm = term,
                            translation = cols.getOrNull(1)?.trim().orEmpty(),
                            aliases = splitAliases(cols.getOrNull(2).orEmpty()),
                            notes = cols.getOrNull(3)?.trim().orEmpty()
                        )
                    )
                }
                continue
            }

            val (mainPart, aliasPart) = if ('|' in line) {
                val idx = line.indexOf('|')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            } else {
                line to ""
            }

            val sepRegex = Regex("\\s*(?:->|=>|=|：|:)\\s*")
            val match = sepRegex.find(mainPart)
            val (term, trans) = if (match != null) {
                mainPart.substring(0, match.range.first).trim() to
                    mainPart.substring(match.range.last + 1).trim()
            } else {
                mainPart.trim() to ""
            }

            if (term.isNotEmpty()) {
                out.add(
                    GlossaryTerm(
                        canonicalTerm = term,
                        translation = trans,
                        aliases = splitAliases(aliasPart)
                    )
                )
            }
        }
        return out
    }

    private fun splitAliases(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', '，', ';', '；', '、', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun csvEscape(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(field.toString())
                        field.clear()
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow.toList())
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow.toList())
                        currentRow.clear()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow.toList())
        }
        return rows
    }
}
