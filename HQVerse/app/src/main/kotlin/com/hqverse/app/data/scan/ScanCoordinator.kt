package com.hqverse.app.data.scan

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScanState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val phase: String = "",
    val foldersDone: Int = 0,
    val foldersTotal: Int = 0,
    val filesFound: Int = 0,
    val processed: Int = 0,
    val toProcess: Int = 0,
    val currentName: String? = null,
    /** Resumo da última varredura (novas, removidas, erros) ou aviso de falha. */
    val message: String? = null,
)

/** Estado observável da varredura + controle de pausa. Vive no processo (a UI lê o mesmo estado do worker). */
class ScanCoordinator {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    @Volatile var paused: Boolean = false
        private set

    fun update(block: (ScanState) -> ScanState) = _state.update(block)

    fun setPaused(value: Boolean) {
        paused = value
        _state.update { it.copy(paused = value) }
    }

    suspend fun awaitIfPaused() {
        while (paused) delay(250)
    }

    fun begin(totalFolders: Int) {
        paused = false
        _state.value = ScanState(running = true, phase = "Procurando arquivos…", foldersTotal = totalFolders)
    }

    fun finish(message: String?) {
        paused = false
        _state.update { it.copy(running = false, paused = false, phase = "", currentName = null, message = message) }
    }
}
