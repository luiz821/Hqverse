package com.hqverse.core

/** Arquivo encontrado na varredura. [uri] identifica o arquivo no SAF. */
data class ScannedFile(val uri: String, val name: String, val size: Long, val lastModified: Long)

/** Arquivo já registrado no banco. */
data class IndexedFile(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)

data class LibraryDiff(
    val added: List<ScannedFile>,
    /** Mesmo URI, mas tamanho/data diferentes: precisa reprocessar capa e páginas. */
    val changed: List<Pair<IndexedFile, ScannedFile>>,
    val unchanged: List<IndexedFile>,
    /** Registro existente que reapareceu com outro URI/nome: o histórico é preservado. */
    val moved: List<Pair<IndexedFile, ScannedFile>>,
    val missing: List<IndexedFile>,
)

object LibraryDiffer {
    /**
     * Compara a varredura atual com o banco. Não reprocessa o que não mudou.
     * Movimentação só é associada quando o par é inequívoco (um único candidato),
     * para nunca transferir progresso para a HQ errada.
     */
    fun diff(scanned: List<ScannedFile>, existing: List<IndexedFile>): LibraryDiff {
        val scannedByUri = LinkedHashMap<String, ScannedFile>()
        scanned.forEach { scannedByUri.putIfAbsent(it.uri, it) }
        val existingByUri = existing.associateBy { it.uri }

        val unchanged = mutableListOf<IndexedFile>()
        val changed = mutableListOf<Pair<IndexedFile, ScannedFile>>()
        val newCandidates = mutableListOf<ScannedFile>()

        for (file in scannedByUri.values) {
            val old = existingByUri[file.uri]
            when {
                old == null -> newCandidates += file
                old.size == file.size && old.lastModified == file.lastModified -> unchanged += old
                else -> changed += old to file
            }
        }
        val missingCandidates = existing.filter { it.uri !in scannedByUri }.toMutableList()
        val moved = mutableListOf<Pair<IndexedFile, ScannedFile>>()
        val remainingNew = newCandidates.toMutableList()

        fun matchBy(predicate: (IndexedFile, ScannedFile) -> Boolean) {
            for (old in missingCandidates.toList()) {
                val matches = remainingNew.filter { predicate(old, it) }
                val rivals = missingCandidates.count { other -> matches.singleOrNull()?.let { predicate(other, it) } == true }
                if (matches.size == 1 && rivals == 1) {
                    moved += old to matches.single()
                    remainingNew.remove(matches.single())
                    missingCandidates.remove(old)
                }
            }
        }
        // 1) mesma pasta movida: mesmo nome e tamanho. 2) renomeado: mesmo tamanho e data de modificação.
        matchBy { o, n -> o.size == n.size && o.name.equals(n.name, ignoreCase = true) }
        matchBy { o, n -> o.size == n.size && o.lastModified == n.lastModified && o.lastModified > 0 }

        return LibraryDiff(remainingNew, changed, unchanged, moved, missingCandidates)
    }
}
