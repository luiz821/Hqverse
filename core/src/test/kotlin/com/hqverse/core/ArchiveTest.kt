package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, data) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry()
            }
        }
        return f
    }

    private fun failure(block: () -> Unit): ReadFailure {
        try { block() } catch (e: ComicReadException) { return e.failure }
        fail("esperava ComicReadException"); throw AssertionError()
    }

    @Test fun cbzLePaginasNaOrdemCorreta() {
        val f = zip("a.cbz", listOf(
            "10.jpg" to byteArrayOf(10), "2.jpg" to byteArrayOf(2), "1.jpg" to byteArrayOf(1),
            "__MACOSX/1.jpg" to byteArrayOf(99), "leia.txt" to byteArrayOf(0),
        ))
        ZipPageSource.open(f).use { src ->
            assertEquals(3, src.pageCount)
            assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), (0 until 3).map { src.pageName(it) })
            assertEquals(2, src.readPage(1)[0].toInt())
        }
    }

    @Test fun cbzComSubpastas() {
        val f = zip("b.cbz", listOf("cap2/1.jpg" to byteArrayOf(21), "cap1/1.jpg" to byteArrayOf(11), "cap1/2.jpg" to byteArrayOf(12)))
        ZipPageSource.open(f).use { src -> assertEquals(listOf(11, 12, 21), (0 until 3).map { src.readPage(it)[0].toInt() }) }
    }

    @Test fun arquivoInexistente() {
        assertEquals(ReadFailure.NOT_FOUND, failure { ZipPageSource.open(File(tmp.root, "nao-existe.cbz")) })
    }

    @Test fun arquivoCorrompido() {
        val f = tmp.newFile("c.cbz"); f.writeBytes(ByteArray(2048) { (it * 7).toByte() })
        assertEquals(ReadFailure.CORRUPT, failure { ZipPageSource.open(f) })
    }

    @Test fun zipTruncado() {
        val noise = ByteArray(20_000).also { java.util.Random(1).nextBytes(it) } // incompressível
        val good = zip("d.cbz", listOf("1.jpg" to noise))
        val raw = good.readBytes()
        val half = tmp.newFile("d2.cbz"); half.writeBytes(raw.copyOf(raw.size / 2))
        assertEquals(ReadFailure.CORRUPT, failure { ZipPageSource.open(half) })
    }

    @Test fun zipSemImagens() {
        val f = zip("e.cbz", listOf("leiame.txt" to byteArrayOf(1)))
        assertEquals(ReadFailure.EMPTY, failure { ZipPageSource.open(f) })
    }

    @Test fun zipVazio() {
        val f = zip("f.cbz", emptyList())
        assertEquals(ReadFailure.EMPTY, failure { ZipPageSource.open(f) })
    }

    @Test fun zipComSenhaEDetectadoSemFecharOApp() {
        val f = zip("g.cbz", listOf("1.jpg" to ByteArray(100) { 5 }))
        val bytes = f.readBytes()
        // marca o bit 0 (criptografado) nos cabeçalhos local (PK 03 04, flags em +6) e central (PK 01 02, flags em +8)
        fun patch(b2: Int, b3: Int, flagOffset: Int) {
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                    bytes[i + 2] == b2.toByte() && bytes[i + 3] == b3.toByte()
                ) bytes[i + flagOffset] = (bytes[i + flagOffset].toInt() or 1).toByte()
            }
        }
        patch(1, 2, 8) // diretório central
        patch(3, 4, 6) // cabeçalho local
        val enc = tmp.newFile("g2.cbz"); enc.writeBytes(bytes)
        assertEquals(ReadFailure.PASSWORD_PROTECTED, failure { ZipPageSource.open(enc) })
    }

    @Test fun limiteDePaginasPorArquivo() {
        val f = zip("h.cbz", (1..20).map { "$it.jpg" to byteArrayOf(1) })
        assertEquals(ReadFailure.TOO_LARGE, failure { ZipPageSource.open(f, ArchiveLimits(maxEntries = 10)) })
    }

    @Test fun protecaoContraDescompressaoExcessiva() {
        // 8 MB de zeros comprimem para poucos KB: razão muito acima do limite
        val f = zip("bomba.cbz", listOf("1.jpg" to ByteArray(8 * 1024 * 1024)))
        assertEquals(ReadFailure.TOO_LARGE, failure { ZipPageSource.open(f, ArchiveLimits(maxRatio = 100)) })
    }

    @Test fun limiteDeTamanhoPorPagina() {
        val f = zip("grande.cbz", listOf("1.jpg" to ByteArray(300_000) { (it * 31).toByte() }))
        assertEquals(ReadFailure.TOO_LARGE, failure { ZipPageSource.open(f, ArchiveLimits(maxEntryBytes = 100_000)) })
    }

    @Test fun leituraLimitadaAbortaMesmoSeCabecalhoMente() {
        assertEquals(ReadFailure.TOO_LARGE, failure { ArchiveSafety.readBounded(ByteArrayInputStream(ByteArray(10_000)), 1_000) })
        assertEquals(500, ArchiveSafety.readBounded(ByteArrayInputStream(ByteArray(500)), 1_000).size)
    }

    @Test fun nomesPerigososSaoRejeitados() {
        assertFalse(ArchiveSafety.isSafeName("../../etc/passwd"))
        assertFalse(ArchiveSafety.isSafeName("a/../../b.jpg"))
        assertFalse(ArchiveSafety.isSafeName("/abs/1.jpg"))
        assertFalse(ArchiveSafety.isSafeName("C:\\x\\1.jpg"))
        assertFalse(ArchiveSafety.isSafeName("a\\..\\b.jpg"))
        assertFalse(ArchiveSafety.isSafeName("a\u0000.jpg"))
        assertFalse(ArchiveSafety.isSafeName(""))
        assertTrue(ArchiveSafety.isSafeName("cap 1/páginá é.jpg"))
    }

    @Test fun zipSlipNuncaViraPagina() {
        val f = zip("slip.cbz", listOf("../evil.jpg" to byteArrayOf(9), "1.jpg" to byteArrayOf(1)))
        ZipPageSource.open(f).use { src -> assertEquals(1, src.pageCount) }
    }

    @Test fun indicePaginaInvalido() {
        val f = zip("i.cbz", listOf("1.jpg" to byteArrayOf(1)))
        ZipPageSource.open(f).use { src -> assertEquals(ReadFailure.NOT_FOUND, failure { src.readPage(5) }) }
    }

    @Test fun cbrFalsoEDetectadoPelosBytesSemAbrirORar() {
        // texto renomeado para .cbr: nenhum leitor é escolhido
        val kind = FormatDetector.detect("isto não é um rar".toByteArray())
        assertEquals(null, FormatDetector.resolveReadFormat(ComicFormat.CBR, kind))
    }
}
