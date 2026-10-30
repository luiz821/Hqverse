package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private fun comic(name: String, size: Long, title: String = "T") = BackupComic(
        key = Fingerprint.of(name, size), fileName = name, sizeBytes = size, title = title, series = "Série \"Ç\"",
        issue = 3, volume = null, year = 2024, author = "Autor\nLinha2", publisher = null, favorite = true,
        wantToRead = false, rating = 4, currentPage = 12, totalPages = 40, readPages = "1-12", status = "IN_PROGRESS",
        startedAt = 1000, lastReadAt = 2000, completedAt = null, activeSeconds = 321, sessionCount = 2,
        notes = "anotação 😀 com \\ barra", tags = listOf("favorita", "2026"),
        sessions = listOf(BackupSession(1000, 1900, 300, 12, 14)),
        bookmarks = listOf(BackupBookmark(7, "Melhor cena", 1500, "#FF0000", "uau"), BackupBookmark(9, null, 1600, null, null)),
    )

    private fun data() = BackupData(
        version = 1, createdAt = 5_000,
        comics = listOf(comic("Batman 003.cbr", 1234), comic("Saga.pdf", 99, "Saga")),
        collections = listOf(BackupCollection("DC", "1-12", listOf(Fingerprint.of("Batman 003.cbr", 1234)))),
        settings = mapOf("theme" to "dark", "columns" to "3"),
    )

    private fun rejects(text: String): BackupFormatException {
        try { BackupCodec.decode(text) } catch (e: BackupFormatException) { return e }
        fail("esperava BackupFormatException"); throw AssertionError()
    }

    @Test fun idaEVoltaPreservaTudo() {
        val original = data()
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test fun idaEVoltaCompactoTambemFunciona() {
        assertEquals(data(), BackupCodec.decode(BackupCodec.encode(data(), pretty = false)))
    }

    @Test fun backupNaoContemCaminhosNemArquivos() {
        val json = BackupCodec.encode(data())
        assertTrue(!json.contains("content://") && !json.contains("file://"))
    }

    @Test fun rejeitaLixo() {
        rejects("isto não é json")
        rejects("[1,2,3]")
        rejects("""{"format":"outro","version":1}""")
        rejects("""{"format":"hqverse-backup"}""")
        rejects("""{"format":"hqverse-backup","version":99,"comics":[]}""")
    }

    @Test fun rejeitaCamposInvalidos() {
        fun withComic(fields: String) = """{"format":"hqverse-backup","version":1,"comics":[{"fileName":"a.cbz","sizeBytes":1,$fields}]}"""
        rejects(withComic(""""rating":9"""))
        rejects(withComic(""""status":"VOANDO""""))
        rejects(withComic(""""readPages":"5-1""""))
        rejects(withComic(""""totalPages":-5"""))
        rejects("""{"format":"hqverse-backup","version":1,"comics":[{"sizeBytes":1}]}""")
        rejects("""{"format":"hqverse-backup","version":1,"comics":[{"fileName":"a","sizeBytes":1},{"fileName":"a","sizeBytes":1}]}""")
        rejects("""{"format":"hqverse-backup","version":1,"comics":[],"collections":[{"name":"x","expectedIssues":"zz"}]}""")
    }

    @Test fun backupMinimoValido() {
        val d = BackupCodec.decode("""{"format":"hqverse-backup","version":1,"comics":[{"fileName":"a.cbz","sizeBytes":10}]}""")
        assertEquals(1, d.comics.size); assertEquals("a.cbz", d.comics[0].title); assertEquals("NOT_STARTED", d.comics[0].status)
    }

    @Test fun reassociaPorNomeETamanhoSoQuandoUnico() {
        val b = listOf(comic("Batman 003.cbr", 1234), comic("Saga.pdf", 99), comic("Dup.cbz", 5))
        val local = listOf(
            BackupMatcher.LocalFile(1, "batman 003.CBR", 1234), BackupMatcher.LocalFile(2, "Saga.pdf", 100),
            BackupMatcher.LocalFile(3, "Dup.cbz", 5), BackupMatcher.LocalFile(4, "dup.cbz", 5),
        )
        val m = BackupMatcher.match(b, local)
        assertEquals(mapOf(Fingerprint.of("Batman 003.cbr", 1234) to 1L), m) // Saga mudou de tamanho; Dup é ambíguo
    }

    @Test fun jsonEscapesEUnicode() {
        val s = "aspas \" barra \\ nova\nlinha tab\t ctrl\u0001 emoji 😀 acento ç"
        assertEquals(s, MiniJson.parse(MiniJson.stringify(s)))
        assertEquals("é", MiniJson.parse("\"\\u00e9\""))
    }

    @Test fun jsonNumerosETipos() {
        assertEquals(42L, MiniJson.parse("42")); assertEquals(-1.5, MiniJson.parse("-1.5")); assertEquals(true, MiniJson.parse("true"))
        assertNull(MiniJson.parse("null"))
        assertEquals(mapOf("a" to listOf(1L, 2L)), MiniJson.parse("""{"a":[1,2]}"""))
    }

    @Test fun jsonMalformadoEProfundidade() {
        for (bad in listOf("{", "[1,", "{\"a\"}", "\"x", "tru", "{\"a\":1} extra", "[1 2]", "\"\\q\"")) {
            try { MiniJson.parse(bad); fail("aceitou: $bad") } catch (_: JsonException) {}
        }
        try { MiniJson.parse("[".repeat(1000)); fail("aceitou aninhamento absurdo") } catch (_: JsonException) {}
    }
}
