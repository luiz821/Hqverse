package com.hqverse.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.BuildConfig
import com.hqverse.app.data.repo.RestorePlan
import com.hqverse.app.data.settings.*
import com.hqverse.app.ui.SettingsViewModel
import com.hqverse.app.ui.components.*
import com.hqverse.core.BackupFormatException
import com.hqverse.core.SortKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onFolders: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val r = vm.repo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var cacheMb by remember { mutableStateOf<Long?>(null) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cacheMb = vm.cacheSizeMb() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val msg = try { "Backup salvo: ${vm.backup.export(uri)} HQs." } catch (e: Exception) { "Falha ao exportar: ${e.message}" }
            busy = false; snackbar.showSnackbar(msg)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { plan = vm.backup.plan(uri) }
            catch (e: BackupFormatException) { snackbar.showSnackbar("Backup inválido: ${e.message}") }
            catch (e: Exception) { snackbar.showSnackbar("Não foi possível ler o arquivo: ${e.message}") }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Configurações", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp)) }

            item { Group("Aparência") {
                ChipRow("Tema", ThemeMode.entries, s.theme, { it.label }) { v -> vm.launch { r.setTheme(v) } }
                Text("Cor de destaque", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accent.entries.forEach { a ->
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(a.argb)).border(if (s.accent == a) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { vm.launch { r.setAccent(a) } }.semantics { contentDescription = "Cor ${a.label}" })
                    }
                }
                ChipRow("Visualização padrão", ViewMode.entries, s.viewMode, { it.label }) { v -> vm.launch { r.setViewMode(v) } }
                ChipRow("Colunas da grade", listOf(0, 2, 3, 4, 5), s.gridColumns, { if (it == 0) "Auto" else "$it" }) { v -> vm.launch { r.setGridColumns(v) } }
                ChipRow("Organização padrão", SortKey.entries, s.sortKey, { it.label }) { v -> vm.launch { r.setSort(v, s.sortDescending) } }
                Switch("Ordem decrescente", s.sortDescending) { v -> vm.launch { r.setSort(s.sortKey, v) } }
                Switch("Reduzir animações", s.reduceMotion) { v -> vm.launch { r.setReduceMotion(v) } }
                Switch("Feedback tátil", s.haptics) { v -> vm.launch { r.setHaptics(v) } }
            } }

            item { Group("Biblioteca") {
                ListItem(headlineContent = { Text("Pastas, arquivos com erro e HQs ocultas") }, supportingContent = { Text("Adicionar, reindexar, pausar ou remover pastas") }, leadingContent = { Icon(Icons.Default.FolderOpen, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onFolders))
                Switch("Atualizar automaticamente ao abrir o app", s.autoScanOnStart) { v -> vm.launch { r.setAutoScanOnStart(v) } }
                Text("A atualização é incremental: só arquivos novos, alterados ou removidos são processados. Apenas as pastas que você autorizou são lidas.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }

            item { Group("Leitor") {
                ChipRow("Modo padrão", ReaderMode.entries, s.readerMode, { it.label }) { v -> vm.launch { r.setReaderMode(v) } }
                ChipRow("Direção de leitura", ReadingDirection.entries, s.direction, { it.label }) { v -> vm.launch { r.setDirection(v) } }
                ChipRow("Ajuste da página", FitMode.entries, s.fitMode, { it.label }) { v -> vm.launch { r.setFitMode(v) } }
                ChipRow("Bloqueio de orientação", OrientationLock.entries, s.orientation, { it.label }) { v -> vm.launch { r.setOrientation(v) } }
                Switch("Animação de mudança de página", s.pageAnimation) { v -> vm.launch { r.setPageAnimation(v) } }
                Switch("Manter a tela ligada", s.keepScreenOn) { v -> vm.launch { r.setKeepScreenOn(v) } }
                Text("Brilho no leitor: " + if (s.brightness < 0) "do sistema" else "${(s.brightness * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(if (s.brightness < 0) 0.5f else s.brightness, { v -> vm.launch { r.setBrightness(v) } }, valueRange = 0.02f..1f, modifier = Modifier.semantics { contentDescription = "Brilho padrão do leitor" })
                TextButton(onClick = { vm.launch { r.setBrightness(-1f) } }) { Text("Usar brilho do sistema") }
            } }

            item { Group("Progresso e tempo") {
                Switch("Salvamento automático", s.autoSave) { v -> vm.launch { r.setAutoSave(v) } }
                Text("Intervalo de salvamento: ${s.saveIntervalSec} s", style = MaterialTheme.typography.bodyMedium)
                Slider(s.saveIntervalSec.toFloat(), { v -> vm.launch { r.setSaveIntervalSec(v.toInt()) } }, valueRange = 5f..120f, modifier = Modifier.semantics { contentDescription = "Intervalo de salvamento" })
                Text("O progresso também é salvo a cada troca de página e ao sair do leitor.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch("Mostrar estimativa de tempo restante", s.showEta) { v -> vm.launch { r.setShowEta(v) } }
                Switch("Contar tempo de leitura", s.trackTime) { v -> vm.launch { r.setTrackTime(v) } }
                Text("Ritmo padrão: ${"%.1f".format(s.defaultPagesPerMinute)} páginas/min (usado até haver histórico suficiente)", style = MaterialTheme.typography.bodyMedium)
                Slider(s.defaultPagesPerMinute, { v -> vm.launch { r.setDefaultPpm((Math.round(v * 10) / 10f)) } }, valueRange = 0.3f..6f, modifier = Modifier.semantics { contentDescription = "Ritmo padrão de leitura" })
                Text("Pausa sem interação conta no máximo ${s.idleCapSec} s", style = MaterialTheme.typography.bodyMedium)
                Slider(s.idleCapSec.toFloat(), { v -> vm.launch { r.setIdleCapSec(v.toInt()) } }, valueRange = 30f..600f, modifier = Modifier.semantics { contentDescription = "Limite de pausa" })
                Text("Regra de conclusão: ${s.completionPercent}% das páginas lidas e chegar ao fim (tolerância de ${s.endTolerancePages} pág. finais)", style = MaterialTheme.typography.bodyMedium)
                Slider(s.completionPercent.toFloat(), { v -> vm.launch { r.setCompletionPercent(v.toInt()) } }, valueRange = 50f..100f, modifier = Modifier.semantics { contentDescription = "Percentual para concluir" })
                Slider(s.endTolerancePages.toFloat(), { v -> vm.launch { r.setEndTolerance(v.toInt()) } }, valueRange = 0f..10f, steps = 9, modifier = Modifier.semantics { contentDescription = "Tolerância de páginas finais" })
                Text("Você sempre pode marcar uma HQ como lida ou não lida manualmente.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }

            item { Group("Cache") {
                Text("Em uso: ${cacheMb?.let { "$it MB" } ?: "calculando…"} · limite ${s.cacheLimitMb} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(s.cacheLimitMb.toFloat(), { v -> vm.launch { r.setCacheLimitMb((v / 64).toInt().coerceAtLeast(1) * 64) } }, valueRange = 64f..4096f, modifier = Modifier.semantics { contentDescription = "Limite do cache" })
                OutlinedButton(onClick = { vm.clearCache { scope.launch { cacheMb = vm.cacheSizeMb(); snackbar.showSnackbar("Cache limpo. As capas serão recriadas quando necessário.") } } }) { Text("Limpar cache") }
            } }

            item { Group("Backup e restauração") {
                Text("Exporta metadados, progresso, histórico, favoritos, coleções, marcadores, anotações e configurações em um arquivo JSON. Os arquivos das HQs não são incluídos.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { export.launch("hqverse-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json") }, enabled = !busy) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Exportar dados") }
                    OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Importar / restaurar") }
                }
                Text("Para restaurar após reinstalar: primeiro adicione a pasta das HQs em Biblioteca e aguarde a varredura; depois importe o backup. Os registros são reassociados por nome e tamanho do arquivo.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }

            item { Group("Sobre o HQVerse") {
                Text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
                Text("Biblioteca e leitor de HQs 100% offline. Sem conta, sem anúncios, sem rastreamento e sem acesso à internet: o app nem declara a permissão de internet.", style = MaterialTheme.typography.bodyMedium)
                Text("Formatos: PDF (leitor nativo do Android), CBZ (ZIP), CBR (RAR4 via junrar) e pastas de imagens (JPG, PNG, WebP). Limitação: CBR em formato RAR5 pode não abrir; arquivos protegidos por senha não são suportados.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Diagnóstico", style = MaterialTheme.typography.titleMedium)
                Text("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nMemória máx. do app: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB\nCache: ${cacheMb ?: 0} MB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Licenças: junrar (MIT/UnRAR modificada — veja o README), AndroidX e Jetpack Compose (Apache 2.0).", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        if (busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
    }

    plan?.let { p ->
        var overwrite by remember { mutableStateOf(false) }
        var withSettings by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { plan = null }, title = { Text("Restaurar backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup de ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(p.data.createdAt))}: ${p.data.comics.size} HQs e ${p.data.collections.size} coleções.")
                    Text("${p.matched.size} HQs encontradas na biblioteca atual serão restauradas. ${p.unmatched.size} não têm arquivo correspondente (adicione a pasta delas e importe de novo).")
                    if (p.conflicts > 0) {
                        Text("${p.conflicts} já têm progresso neste aparelho.", color = MaterialTheme.colorScheme.error)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(overwrite, { overwrite = it }); Text("Sobrescrever o progresso existente") }
                        if (!overwrite) Text("Sem marcar, o progresso atual é mantido.", style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withSettings, { withSettings = it }); Text("Restaurar também as configurações") }
                }
            },
            confirmButton = { TextButton(onClick = {
                val pl = p; plan = null
                scope.launch { busy = true; val n = try { vm.backup.apply(pl, overwrite, withSettings) } catch (e: Exception) { -1 }; busy = false; snackbar.showSnackbar(if (n >= 0) "$n HQs restauradas." else "Falha ao restaurar.") }
            }, enabled = p.matched.isNotEmpty() || p.data.collections.isNotEmpty() || withSettings) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); content() }
    }
}

@Composable
private fun Switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Switch(checked, onChange)
    }
}
