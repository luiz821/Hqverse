package com.hqverse.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.ui.LibraryViewModel
import com.hqverse.app.ui.components.*
import com.hqverse.core.ComicRow
import com.hqverse.core.ReadStatus
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bom dia"; in 12..17 -> "Boa tarde"; else -> "Boa noite"
}

@Composable
fun HomeScreen(vm: LibraryViewModel, onPickFolder: () -> Unit, onOpen: (Long) -> Unit, onRead: (Long) -> Unit, onSeeLibrary: () -> Unit, onCollections: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val continueList by vm.continueReading.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val overall by vm.overallPercent.collectAsStateWithLifecycle()

    if (rows == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (folders.isEmpty() && rows.orEmpty().isEmpty()) { WelcomeScreen(onPickFolder); return }

    val current = continueList.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(greeting() + " 👋", style = MaterialTheme.typography.headlineMedium)
                Text("Sua coleção, no seu ritmo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (scan.running) item { ScanBanner(vm, scan) }
        scan.message?.takeIf { !scan.running }?.let { msg -> item { MessageBanner(msg, onDismiss = { vm.dismissScanMessage() }) } }
        if (current != null) item { ContinueHero(current, onRead = { onRead(current.id) }, onOpen = { onOpen(current.id) }) }
        else if (rows.orEmpty().isNotEmpty()) item {
            Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp)) {
                    Text("Pronto para ler?", style = MaterialTheme.typography.titleLarge)
                    Text("Escolha uma HQ na biblioteca para começar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp)); FilledTonalButton(onClick = onSeeLibrary) { Text("Abrir biblioteca") }
                }
            }
        }
        item { OverallProgress(overall, rows.orEmpty()) }
        if (continueList.size > 1) item { Shelf("Retomar leituras interrompidas", continueList.drop(1), onOpen) }
        if (recent.isNotEmpty()) item { Shelf("Adições recentes", recent, onOpen) }
        if (favorites.isNotEmpty()) item { Shelf("Favoritas", favorites, onOpen) }
        if (collections.isNotEmpty()) item {
            SectionTitle("Coleções", action = { TextButton(onClick = onCollections) { Text("Ver todas") } })
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(collections, key = { it.collection.id }) { c ->
                    AssistChip(onClick = onCollections, label = { Text("${c.collection.name} · ${c.itemCount}") }, leadingIcon = { Icon(Icons.Default.Folder, null) })
                }
            }
        }
    }
}

@Composable
private fun ContinueHero(c: ComicEntity, onRead: () -> Unit, onOpen: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp,
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainer)))) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(c, Modifier.width(112.dp), corner = 16.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("LENDO AGORA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(c.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Página ${c.currentPage} de ${c.pageCount} · ${c.percent()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp)); ProgressBar(c.percent() / 100f)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRead, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Continuar lendo") }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun OverallProgress(percent: Int, rows: List<ComicRow>) {
    val visible = rows.filter { !it.hidden && !it.missing }
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Progresso geral", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("$percent%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp)); ProgressBar(percent / 100f, height = 8.dp)
            Spacer(Modifier.height(10.dp))
            Text("${visible.count { it.status == ReadStatus.COMPLETED }} concluídas · ${visible.count { it.status == ReadStatus.IN_PROGRESS || it.status == ReadStatus.PAUSED }} em andamento · ${visible.count { it.status == ReadStatus.NOT_STARTED }} não iniciadas",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun Shelf(title: String, items: List<ComicEntity>, onOpen: (Long) -> Unit) {
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { c -> ComicGridCard(c, onClick = { onOpen(c.id) }, modifier = Modifier.width(128.dp)) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun ScanBanner(vm: LibraryViewModel, s: com.hqverse.app.data.scan.ScanState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(if (s.paused) "Varredura pausada" else s.phase.ifEmpty { "Atualizando biblioteca…" }, style = MaterialTheme.typography.titleMedium)
            val detail = if (s.toProcess > 0) "${s.processed} de ${s.toProcess} processadas" else "${s.filesFound} arquivos encontrados"
            Text(detail + (s.currentName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (s.toProcess > 0) ProgressBar(s.processed.toFloat() / s.toProcess) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { vm.pauseScan(!s.paused) }) { Text(if (s.paused) "Retomar" else "Pausar") }
                TextButton(onClick = { vm.cancelScan(context) }) { Text("Cancelar") }
            }
        }
    }
}

@Composable
fun MessageBanner(msg: String, onDismiss: () -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dispensar aviso") }
        }
    }
}

@Composable
fun WelcomeScreen(onPickFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            Icons.Default.AutoStories, "Bem-vindo ao HQVerse",
            "Sua biblioteca de quadrinhos, 100% offline. Escolha a pasta onde estão suas HQs (PDF, CBR, CBZ ou pastas de imagens) e o app organiza tudo automaticamente.",
            action = { Button(onClick = onPickFolder, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Escolher pasta de HQs") } },
        )
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Como funciona", style = MaterialTheme.typography.titleMedium)
                Text("1. Toque no botão e selecione uma pasta (Download, Documents, HQs…).", style = MaterialTheme.typography.bodyMedium)
                Text("2. Toque em \"Usar esta pasta\" para autorizar. O app só lê essa pasta, nada além.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Aguarde a varredura: capas e progresso aparecem aos poucos.", style = MaterialTheme.typography.bodyMedium)
                Text("Nenhum arquivo é movido, copiado para fora nem enviado para a internet.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
