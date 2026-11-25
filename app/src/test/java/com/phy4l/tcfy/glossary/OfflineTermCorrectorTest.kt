package com.phy4l.tcfy.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTermCorrectorTest {

    private val physicsTerms = listOf(
        GlossaryTerm(
            canonicalTerm = "Hamiltonian",
            translation = "哈密顿量",
            aliases = listOf("hamitonian", "哈米顿")
        ),
        GlossaryTerm(
            canonicalTerm = "薛定谔方程",
            translation = "Schrödinger equation",
            aliases = listOf("学定额方程")
        ),
        GlossaryTerm(
            canonicalTerm = "Eigenvalue",
            translation = "特征值",
            aliases = listOf("艾根value")
        ),
        GlossaryTerm(
            canonicalTerm = "Schrödinger",
            translation = "薛定谔",
            aliases = emptyList()
        ),
        GlossaryTerm(
            canonicalTerm = "拉格朗日量",
            translation = "Lagrangian",
            aliases = emptyList()
        )
    )

    @Test
    fun replacesExplicitAliasesCaseInsensitivelyAndLongestFirst() {
        val corrector = OfflineTermCorrector(physicsTerms)
        val res = corrector.correct("今天我们求解 Hamitonian 的 艾根value 和 学定额方程")
        assertEquals("今天我们求解 Hamiltonian 的 Eigenvalue 和 薛定谔方程", res.correctedText)
        assertEquals(3, res.spans.size)
    }

    @Test
    fun correctsChineseHomophonesViaPinyinFuzzyWithoutExplicitAlias() {
        val corrector = OfflineTermCorrector(physicsTerms, enablePinyinFuzzy = true)
        // "哈密顿良" is NOT in aliases, only "哈密顿量" is in translation;
        // "雪定鹅" is NOT in aliases, only "薛定谔" is in translation!
        val res = corrector.correct("这里考虑哈密顿良以及雪定鹅表象下的拉哥朗日良")
        assertTrue("Expected 哈密顿量 in: ${res.correctedText}", res.correctedText.contains("哈密顿量"))
        assertTrue("Expected 薛定谔 in: ${res.correctedText}", res.correctedText.contains("薛定谔"))
        assertTrue("Expected 拉格朗日量 in: ${res.correctedText}", res.correctedText.contains("拉格朗日量"))
        assertTrue(res.spans.any { it.matchReason == "pinyin" })
    }

    @Test
    fun correctsEnglishTypoViaFuzzyEditDistance() {
        val corrector = OfflineTermCorrector(physicsTerms, enableEnglishFuzzy = true)
        val res = corrector.correct("We compute the eignvalue of the Schrodinger operator.")
        assertEquals("We compute the Eigenvalue of the Schrödinger operator.", res.correctedText)
    }

    @Test
    fun doesNotCorruptNormalUnrelatedChineseProse() {
        val corrector = OfflineTermCorrector(physicsTerms)
        val sentence = "同学们下课后记得把今天的实验报告交到教务处。"
        val res = corrector.correct(sentence)
        assertEquals(sentence, res.correctedText)
        assertFalse(res.hasChanges)
    }
}
