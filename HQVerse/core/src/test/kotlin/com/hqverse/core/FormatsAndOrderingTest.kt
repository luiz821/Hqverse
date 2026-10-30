package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsAndOrderingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun formatoPeloNome() {
        assertEquals(ComicFormat.PDF, FormatDetector.formatFromName("a.PDF"))
        assertEquals(ComicFormat.CBR, FormatDetector.formatFromName("a.cbr"))
        assertEquals(ComicFormat.CBZ, FormatDetector.formatFromName("a.CBZ"))
        assertNull(FormatDetector.formatFromName("a.txt"))
        assertNull(FormatDetector.formatFromName("foto.jpg"))
    }

    @Test fun assinaturasDeArquivo() {
        assertEquals(DetectedKind.PDF, FormatDetector.detect("%PDF-1.7".toByteArray()))
        assertEquals(DetectedKind.ZIP, FormatDetector.detect(bytes(0x50, 0x4B, 3, 4, 0)))
        assertEquals(DetectedKind.RAR, FormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 0)))
        assertEquals(DetectedKind.RAR, FormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 1, 0)))
        assertEquals(DetectedKind.SEVEN_ZIP, FormatDetector.detect(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        assertEquals(DetectedKind.JPEG, FormatDetector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(DetectedKind.UNKNOWN, FormatDetector.detect(ByteArray(0)))
        assertEquals(DetectedKind.UNKNOWN, FormatDetector.detect("texto qualquer".toByteArray()))
    }

    @Test fun distingueRar4DeRar5() {
        assertTrue(FormatDetector.isRar5(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 1, 0)))
        assertFalse(FormatDetector.isRar5(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 0, 0xCF)))
        assertFalse(FormatDetector.isRar5(bytes(0x50, 0x4B, 3, 4, 0, 0, 1, 0)))
        assertTrue(ReadFailure.RAR5_UNSUPPORTED.userMessage.contains("RAR5"))
    }

    @Test fun conteudoRealPrevaleceSobreExtensao() {
        // "CBR" que na verdade é ZIP deve abrir como CBZ.
        assertEquals(ComicFormat.CBZ, FormatDetector.resolveReadFormat(ComicFormat.CBR, DetectedKind.ZIP))
        assertEquals(ComicFormat.CBR, FormatDetector.resolveReadFormat(ComicFormat.CBZ, DetectedKind.RAR))
        assertNull(FormatDetector.resolveReadFormat(ComicFormat.CBR, DetectedKind.SEVEN_ZIP))
        assertNull(FormatDetector.resolveReadFormat(ComicFormat.PDF, DetectedKind.UNKNOWN))
    }

    @Test fun pastaDeImagens() {
        assertFalse(FolderComicRule.isComicFolder(2))
        assertTrue(FolderComicRule.isComicFolder(3))
    }

    @Test fun ordemNatural() {
        val sorted = listOf("p10.jpg", "p2.jpg", "p1.jpg", "p02a.jpg").sortedWith(NaturalOrder)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p02a.jpg", "p10.jpg"), sorted)
    }

    @Test fun ordemNaturalComNumerosEnormesNaoEstoura() {
        val big = "x" + "9".repeat(60)
        assertTrue(NaturalOrder.compare("x1", big) < 0)
    }

    @Test fun paginasIgnoramLixoEOrdenamComDiretorios() {
        val entries = listOf(
            "__MACOSX/cap1/._001.jpg", "cap1/", "cap2/002.jpg", "cap1/010.jpg", "cap1/002.jpg",
            "cap2/001.png", "Thumbs.db", "leiame.txt", ".hidden.jpg", "cap1\\001.webp", "capa.jpg",
        )
        assertEquals(
            listOf("cap1\\001.webp", "cap1/002.jpg", "cap1/010.jpg", "cap2/001.png", "cap2/002.jpg", "capa.jpg"),
            PageOrdering.sortPages(entries),
        )
    }

    @Test fun mensagensDeErroEmPortugues() {
        ReadFailure.values().forEach { assertTrue(it.userMessage.isNotBlank()) }
        assertTrue(ReadFailure.PASSWORD_PROTECTED.userMessage.contains("senha"))
    }
}
