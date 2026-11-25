package com.phy4l.tcfy.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class GlossaryImporterTest {

    @Test
    fun parsesAndExportsCsvWithQuotesAndCommas() {
        val csv = """
            term,translation,aliases,notes
            Hamiltonian,哈密顿量,"哈密顿良, 哈米顿","总能量算符, H = T + V"
            "Fourier transform",傅里叶变换,福利叶变换;傅立叶变换,频域变换
        """.trimIndent()

        val parsed = GlossaryImporter.parseCsv(csv)
        assertEquals(2, parsed.size)
        assertEquals("Hamiltonian", parsed[0].canonicalTerm)
        assertEquals("哈密顿量", parsed[0].translation)
        assertEquals(listOf("哈密顿良", "哈米顿"), parsed[0].aliases)
        assertEquals("总能量算符, H = T + V", parsed[0].notes)

        val reExported = GlossaryImporter.exportCsv(parsed)
        val roundTripped = GlossaryImporter.parseCsv(reExported)
        assertEquals(parsed, roundTripped)
    }

    @Test
    fun parsesFlexibleTxtLines() {
        val txt = """
            # Quantum Mechanics
            Eigenvalue = 特征值 | 特征只, 艾根value
            Wavefunction -> 波函数
            Commutator
        """.trimIndent()

        val parsed = GlossaryImporter.parseTxt(txt)
        assertEquals(3, parsed.size)
        assertEquals("Eigenvalue", parsed[0].canonicalTerm)
        assertEquals("特征值", parsed[0].translation)
        assertEquals(listOf("特征只", "艾根value"), parsed[0].aliases)
        assertEquals("波函数", parsed[1].translation)
        assertEquals("Commutator", parsed[2].canonicalTerm)
    }

    @Test
    fun decodesGbkBytesFromChineseWindowsExcel() {
        val original = "术语,译名\n薛定谔,Schrödinger"
        val gbkBytes = original.toByteArray(Charset.forName("GBK"))
        val decoded = GlossaryImporter.decodeBytesSmart(gbkBytes)
        assertTrue(decoded.contains("薛定谔"))
    }

    @Test
    fun roundTripsJsonGlossaryPack() {
        val json = GlossaryImporter.exportJson(BuiltInGlossaries.PHYSICS_STARTER)
        val restored = GlossaryImporter.parseJson(json)
        assertEquals(BuiltInGlossaries.PHYSICS_STARTER.name, restored.name)
        assertEquals(BuiltInGlossaries.PHYSICS_STARTER.terms.size, restored.terms.size)
    }
}
