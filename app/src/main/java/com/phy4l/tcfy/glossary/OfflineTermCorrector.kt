package com.phy4l.tcfy.glossary

/**
 * Fast offline real-time terminology correction engine (<2ms per sentence).
 *
 * Applies three layered passes without requiring any network connection:
 * 1. Deterministic longest-match-first alias replacement (exact/case-insensitive for Latin).
 * 2. CJK phonetic homophone correction for domain terms (>=3 chars, high similarity threshold).
 * 3. English/Latin fuzzy edit-distance correction for technical vocabulary (>=6 chars).
 *
 * Tracks every replacement span so the UI can highlight auto-corrected terms and allow 1-tap undo.
 */
class OfflineTermCorrector(
    terms: List<GlossaryTerm>,
    private val enablePinyinFuzzy: Boolean = true,
    private val enableEnglishFuzzy: Boolean = true
) {
    private data class AliasRule(
        val alias: String,
        val isLatinWord: Boolean,
        val term: GlossaryTerm
    )

    private val aliasRules: List<AliasRule>
    private val cjkTerms: List<Pair<String, GlossaryTerm>> // (targetCjkString, term)
    private val latinTerms: List<Pair<String, GlossaryTerm>> // (normalizedLatin, term)

    init {
        val rules = mutableListOf<AliasRule>()
        val cjkCandidates = mutableListOf<Pair<String, GlossaryTerm>>()
        val latinCandidates = mutableListOf<Pair<String, GlossaryTerm>>()

        for (t in terms) {
            val canonical = t.canonicalTerm.trim()
            if (canonical.isEmpty()) continue

            for (rawAlias in t.aliases) {
                val alias = rawAlias.trim()
                if (alias.isNotEmpty() && !alias.equals(canonical, ignoreCase = false)) {
                    rules.add(
                        AliasRule(
                            alias = alias,
                            isLatinWord = alias.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' },
                            term = t
                        )
                    )
                }
            }

            // Collect CJK domain terms (both canonical and Chinese translation if canonical is English!)
            if (PhoneticUtils.isPureCjk(canonical) && canonical.length >= 3) {
                cjkCandidates.add(canonical to t)
            }
            val trans = t.translation.trim()
            if (PhoneticUtils.isPureCjk(trans) && trans.length >= 3 && trans != canonical) {
                // If user defined English canonical "Hamiltonian" + translation "哈密顿量",
                // also correct Chinese homophone "哈密顿良" -> "哈密顿量"!
                cjkCandidates.add(trans to t.copy(canonicalTerm = trans, translation = canonical))
            }

            // Collect single-word technical Latin terms >= 6 chars
            if (canonical.length >= 6 && canonical.none { PhoneticUtils.isCjkChar(it) || it.isWhitespace() }) {
                latinCandidates.add(PhoneticUtils.normalizeLatin(canonical) to t)
            }
        }

        // Longest-match-first prevents shorter aliases from partially clobbering longer ones
        aliasRules = rules.sortedByDescending { it.alias.length }
        cjkTerms = cjkCandidates.distinctBy { it.first }.sortedByDescending { it.first.length }
        latinTerms = latinCandidates.distinctBy { it.first }.sortedByDescending { it.first.length }
    }

    fun correct(rawText: String): CorrectionResult {
        if (rawText.isBlank()) {
            return CorrectionResult(rawText, rawText, emptyList())
        }

        var currentText = rawText
        val allSpans = mutableListOf<TermCorrectionSpan>()

        // Pass 1: Exact / alias replacement
        val (afterAlias, aliasSpans) = applyAliasPass(currentText)
        currentText = afterAlias
        allSpans.addAll(aliasSpans)

        // Pass 2: CJK phonetic homophone correction (skipping spans already corrected)
        if (enablePinyinFuzzy && cjkTerms.isNotEmpty() && PhoneticUtils.containsCjk(currentText)) {
            val (afterPinyin, pinyinSpans) = applyPinyinFuzzyPass(currentText, allSpans)
            currentText = afterPinyin
            allSpans.addAll(pinyinSpans)
        }

        // Pass 3: English fuzzy edit-distance correction
        if (enableEnglishFuzzy && latinTerms.isNotEmpty()) {
            val (afterLatin, latinSpans) = applyLatinFuzzyPass(currentText, allSpans)
            currentText = afterLatin
            allSpans.addAll(latinSpans)
        }

        return CorrectionResult(
            originalText = rawText,
            correctedText = currentText,
            spans = allSpans.sortedBy { it.startIndex }
        )
    }

    private fun applyAliasPass(text: String): Pair<String, List<TermCorrectionSpan>> {
        if (aliasRules.isEmpty()) return text to emptyList()

        val result = StringBuilder()
        val spans = mutableListOf<TermCorrectionSpan>()
        var i = 0
        while (i < text.length) {
            var matched: AliasRule? = null
            for (rule in aliasRules) {
                val len = rule.alias.length
                if (i + len > text.length) continue
                val sub = text.substring(i, i + len)
                val matches = if (rule.isLatinWord) {
                    sub.equals(rule.alias, ignoreCase = true) && isWordBoundary(text, i, i + len)
                } else {
                    sub == rule.alias
                }
                if (matches) {
                    matched = rule
                    break
                }
            }

            if (matched != null) {
                val orig = text.substring(i, i + matched.alias.length)
                val startOut = result.length
                val replacement = matched.term.canonicalTerm
                result.append(replacement)
                spans.add(
                    TermCorrectionSpan(
                        startIndex = startOut,
                        endIndex = result.length,
                        originalText = orig,
                        correctedText = replacement,
                        translation = matched.term.translation,
                        matchReason = "alias"
                    )
                )
                i += matched.alias.length
            } else {
                result.append(text[i])
                i++
            }
        }
        return result.toString() to spans
    }

    private fun applyPinyinFuzzyPass(
        text: String,
        existingSpans: List<TermCorrectionSpan>
    ): Pair<String, List<TermCorrectionSpan>> {
        val chars = text.toCharArray()
        val locked = BooleanArray(chars.size)
        for (span in existingSpans) {
            for (idx in span.startIndex.coerceAtLeast(0) until span.endIndex.coerceAtMost(locked.size)) {
                locked[idx] = true
            }
        }

        val newSpans = mutableListOf<TermCorrectionSpan>()
        // Same-length CJK replacements preserve indices, so existingSpans stay valid!
        for ((targetCjk, term) in cjkTerms) {
            val len = targetCjk.length
            var i = 0
            while (i + len <= chars.size) {
                var anyLocked = false
                var allCjk = true
                for (k in 0 until len) {
                    if (locked[i + k]) {
                        anyLocked = true
                        break
                    }
                    if (!PhoneticUtils.isCjkChar(chars[i + k])) {
                        allCjk = false
                        break
                    }
                }
                if (anyLocked || !allCjk) {
                    i++
                    continue
                }

                val window = String(chars, i, len)
                if (window == targetCjk) {
                    // Already exact match -> lock it so nothing else mutates it
                    for (k in 0 until len) locked[i + k] = true
                    i += len
                    continue
                }

                val similarity = PhoneticUtils.cjkPhoneticSimilarity(window, targetCjk)
                val threshold = when {
                    len >= 4 -> 0.80
                    else -> 0.88 // 3-char terms require very high phonetic confidence
                }

                if (similarity >= threshold) {
                    for (k in 0 until len) {
                        chars[i + k] = targetCjk[k]
                        locked[i + k] = true
                    }
                    newSpans.add(
                        TermCorrectionSpan(
                            startIndex = i,
                            endIndex = i + len,
                            originalText = window,
                            correctedText = targetCjk,
                            translation = term.translation,
                            matchReason = "pinyin"
                        )
                    )
                    i += len
                } else {
                    i++
                }
            }
        }

        return String(chars) to newSpans
    }

    private fun applyLatinFuzzyPass(
        text: String,
        existingSpans: MutableList<TermCorrectionSpan>
    ): Pair<String, List<TermCorrectionSpan>> {
        val wordRegex = Regex("[A-Za-z][A-Za-z0-9\\-']{5,}")
        val matches = wordRegex.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val result = StringBuilder()
        val newSpans = mutableListOf<TermCorrectionSpan>()
        var cursor = 0
        var deltaOffset = 0

        for (m in matches) {
            val range = m.range
            val start = range.first
            val end = range.last + 1

            // Skip words already covered by earlier spans
            val overlapsExisting = existingSpans.any { span ->
                start < (span.endIndex - deltaOffset) && end > (span.startIndex - deltaOffset)
            }
            if (overlapsExisting) continue

            val word = m.value
            val normalizedWord = PhoneticUtils.normalizeLatin(word)

            var bestMatch: Pair<String, GlossaryTerm>? = null
            var bestDist = Int.MAX_VALUE
            for (candidate in latinTerms) {
                val targetNorm = candidate.first
                if (normalizedWord == targetNorm) {
                    // Exact spelling (or differs only by casing/diacritics)
                    if (word != candidate.second.canonicalTerm) {
                        bestMatch = candidate
                        bestDist = 0
                    } else {
                        bestMatch = null
                    }
                    break
                }
                val maxAllowedDist = if (targetNorm.length >= 9) 2 else 1
                if (kotlin.math.abs(normalizedWord.length - targetNorm.length) > maxAllowedDist) continue
                // Require first letter match to avoid unrelated short-distance collisions
                if (normalizedWord.first() != targetNorm.first()) continue
                val dist = PhoneticUtils.levenshtein(normalizedWord, targetNorm)
                if (dist in 1..maxAllowedDist && dist < bestDist) {
                    bestDist = dist
                    bestMatch = candidate
                }
            }

            if (bestMatch != null) {
                result.append(text, cursor, start)
                val outStart = result.length
                val replacement = bestMatch.second.canonicalTerm
                result.append(replacement)
                val lengthDiff = replacement.length - word.length

                // Shift existing spans that come after this replacement
                for (idx in existingSpans.indices) {
                    val s = existingSpans[idx]
                    if (s.startIndex >= outStart) {
                        existingSpans[idx] = s.copy(
                            startIndex = s.startIndex + lengthDiff,
                            endIndex = s.endIndex + lengthDiff
                        )
                    }
                }
                deltaOffset += lengthDiff
                newSpans.add(
                    TermCorrectionSpan(
                        startIndex = outStart,
                        endIndex = result.length,
                        originalText = word,
                        correctedText = replacement,
                        translation = bestMatch.second.translation,
                        matchReason = "fuzzy_en"
                    )
                )
                cursor = end
            }
        }

        result.append(text, cursor, text.length)
        return result.toString() to newSpans
    }

    private fun isWordBoundary(text: String, start: Int, end: Int): Boolean {
        val leftOk = start == 0 || !isLatinWordChar(text[start - 1])
        val rightOk = end == text.length || !isLatinWordChar(text[end])
        return leftOk && rightOk
    }

    private fun isLatinWordChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
}
