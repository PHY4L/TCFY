package com.phy4l.tcfy.glossary

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import kotlin.math.min

/**
 * Lightweight Hanzi-to-Pinyin and phonetic similarity utility backed by pinyin4j.
 *
 * Collapses common classroom/dialect ASR confusions (zh/z, ch/c, sh/s, n/l, in/ing, en/eng, an/ang)
 * so far-field homophone errors like "学定额" -> "薛定谔" or "哈密顿良" -> "哈密顿量" can be caught offline.
 */
object PhoneticUtils {

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
    }

    fun isPureCjk(text: String): Boolean = text.isNotEmpty() && text.all { isCjkChar(it) }

    fun containsCjk(text: String): Boolean = text.any { isCjkChar(it) }

    /**
     * Returns all possible toneless pinyin readings for a single CJK char (handles polyphones).
     */
    fun charPinyins(ch: Char): List<String> {
        if (!isCjkChar(ch)) return listOf(ch.lowercase())
        val raw = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat) }.getOrNull()
        if (raw == null || raw.isEmpty()) return listOf(ch.lowercase())
        return raw.distinct()
    }

    /**
     * Normalizes a pinyin syllable to collapse common far-field/accent confusions.
     */
    fun normalizeFuzzySyllable(syllable: String): String {
        var s = syllable.lowercase().trim()
        if (s.startsWith("zh")) s = "z" + s.substring(2)
        else if (s.startsWith("ch")) s = "c" + s.substring(2)
        else if (s.startsWith("sh")) s = "s" + s.substring(2)
        else if (s.startsWith("l") && s.length > 1) s = "n" + s.substring(1)
        else if (s.startsWith("f") && s.length > 1 && s[1] == 'u') s = "hu" + s.substring(2)

        if (s.endsWith("ing")) s = s.dropLast(1)
        else if (s.endsWith("eng")) s = s.dropLast(1)
        else if (s.endsWith("ang")) s = s.dropLast(1)

        s = s.replace("ue", "ve").replace("v", "u")
        return s
    }

    /**
     * Computes syllable-level phonetic similarity between two equal-length CJK strings (0.0..1.0).
     * Exact syllable match = 1.0, fuzzy-normalized match = 0.90, 1-char edit = 0.55.
     */
    fun cjkPhoneticSimilarity(a: String, b: String): Double {
        if (a.length != b.length || a.isEmpty()) return 0.0
        var totalScore = 0.0
        for (i in a.indices) {
            if (a[i] == b[i]) {
                totalScore += 1.0
                continue
            }
            val pA = charPinyins(a[i])
            val pB = charPinyins(b[i])
            var best = 0.0
            for (sa in pA) {
                for (sb in pB) {
                    val score = when {
                        sa == sb -> 1.0
                        normalizeFuzzySyllable(sa) == normalizeFuzzySyllable(sb) -> 0.92
                        levenshtein(normalizeFuzzySyllable(sa), normalizeFuzzySyllable(sb)) == 1 &&
                            maxOf(sa.length, sb.length) >= 3 -> 0.60
                        else -> 0.0
                    }
                    if (score > best) best = score
                }
            }
            totalScore += best
        }
        return totalScore / a.length
    }

    /**
     * Standard Levenshtein edit distance for Latin/ASCII technical terms.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Normalizes Latin accents/diacritics for fuzzy academic term matching (e.g. Schrödinger -> schrodinger).
     */
    fun normalizeLatin(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when (ch) {
                'ö', 'ó', 'ò', 'ô', 'õ' -> sb.append('o')
                'ä', 'á', 'à', 'â', 'ã' -> sb.append('a')
                'ü', 'ú', 'ù', 'û' -> sb.append('u')
                'é', 'è', 'ê', 'ë' -> sb.append('e')
                'í', 'ì', 'î', 'ï' -> sb.append('i')
                'ñ' -> sb.append('n')
                'ç' -> sb.append('c')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
