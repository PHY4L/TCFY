package com.phy4l.tcfy.glossary

import kotlinx.serialization.Serializable

@Serializable
data class GlossaryTerm(
    val id: Long = 0L,
    val canonicalTerm: String,
    val translation: String = "",
    val aliases: List<String> = emptyList(),
    val notes: String = ""
)

@Serializable
data class GlossaryPackExport(
    val name: String,
    val description: String = "",
    val terms: List<GlossaryTerm>
)

@Serializable
data class TermCorrectionSpan(
    val startIndex: Int,
    val endIndex: Int,
    val originalText: String,
    val correctedText: String,
    val translation: String = "",
    val matchReason: String = "" // "alias", "pinyin", "fuzzy_en"
)

data class CorrectionResult(
    val originalText: String,
    val correctedText: String,
    val spans: List<TermCorrectionSpan>
) {
    val hasChanges: Boolean get() = spans.isNotEmpty()
}
