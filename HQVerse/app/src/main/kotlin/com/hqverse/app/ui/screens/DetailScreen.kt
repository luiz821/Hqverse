package com.hqverse.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.ui.DetailViewModel
import com.hqverse.app.ui.components.*
import com.hqverse.core.PaceSource
import com.hqverse.core.ReadStatus
import java.text.DateFormat
import java.util.Date

private fun fmtDate(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "—"
private fun fmtSize(b: Long): String = when { b >= 1 shl 30 -> "%.2f GB".format(b / 1073741824.0); b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0); else -> "${b / 1024} KB" }
fun fmtDuration(sec: Long): String = when { sec < 60 -> "${sec} s"; sec < 3600 -> "${sec / 60} min"; else -> "${sec / 3600} h ${(sec % 3600) / 60} min" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit, onRead: (page: Int?) -> Unit, onOpenCollection: (Long) -> Unit) {
    val comic by vm.comic.collectAsStateWithLifecycle()
    val c = comic
    if (c == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val eta by vm.eta.collectAsStateWithLifecycle()
    val memberOf by vm.collectionIds.collectAsStateWithLifecycle()
    val allCollections by vm.collections.collectAsStateWithLifecycle()
    val folderName by vm.folderName.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var dialog by remember { mutableStateOf<String?>(null) }
    var editingBookmark by remember { mutableStateOf<com.hqverse.app.data.db.BookmarkEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    val started = c.readStatus() != ReadStatus.NOT_STARTED && c.currentPage > 1 || c.readPages.isNotEmpty()
    val completed = c.readStatus() == ReadStatus.COMPLETED
    val readable = c.errorCode == null && !c.missing

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Box {
                    Box(Modifier.fillMaxWidth().height(320.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), MaterialTheme.colorScheme.background))))
                    IconButton(onClick = onBack, Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverImage(c, Modifier.width(170.dp), corner = 20.dp)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.title, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    val meta = listOfNotNull(c.series?.takeIf { it != c.title }, c.issue?.let { "Edição $it" }, c.volume?.let { "Vol. $it" }, c.year?.toString(), c.author, c.publisher).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(8.dp)); StatusBadge(c)
                    failureText(c.errorCode)?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center); TextButton(onClick = { vm.retry() }) { Text("Tentar abrir de novo") } }
                    if (c.missing) Text("Este arquivo não foi encontrado na última varredura. Seus dados de leitura foram mantidos.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onRead(null) }, enabled = readable, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                        Text(if (completed) "Ler novamente" else if (started) "Continuar lendo (pág. ${c.currentPage})" else "Ler agora", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        FilledTonalButton(onClick = { vm.favorite(!c.favorite) }) { Icon(if (c.favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (c.favorite) Color(0xFFFF6B9A) else LocalContentColorOr()); Spacer(Modifier.width(6.dp)); Text(if (c.favorite) "Favorita" else "Favoritar") }
                        FilledTonalButton(onClick = { vm.wantToRead(!c.wantToRead) }) { Icon(Icons.Default.BookmarkAdded, null); Spacer(Modifier.width(6.dp)); Text(if (c.wantToRead) "Na lista Quero ler" else "Quero ler") }
                        if (completed) FilledTonalButton(onClick = { vm.markUnread() }) { Text("Marcar como não lida") } else FilledTonalButton(onClick = { vm.markRead() }) { Icon(Icons.Default.DoneAll, null); Spacer(Modifier.width(6.dp)); Text("Marcar como lida") }
                        FilledTonalButton(onClick = { dialog = "collections" }) { Icon(Icons.Default.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Coleção") }
                        FilledTonalButton(onClick = { dialog = "edit" }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Editar informações") }
                        FilledTonalButton(onClick = { dialog = "bookmark" }, enabled = readable) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text("Adicionar marcador") }
                        FilledTonalButton(onClick = { dialog = "cover" }, enabled = readable) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Trocar capa") }
                        FilledTonalButton(onClick = { dialog = "history" }) { Icon(Icons.Default.History, null); Spacer(Modifier.width(6.dp)); Text("Histórico") }
                    }
                }
            }
            // Progresso e estimativa
            item {
                ProgressCard(c.pageCount, c, eta, settings.showEta, onSetPage = { dialog = "page" }, onTotal = { dialog = "total" }, onReset = { dialog = "reset" }, onPause = { vm.setPaused(!c.manuallyPaused) })
            }
            item {
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Avaliação", style = MaterialTheme.typography.titleMedium)
                        Row { (1..5).forEach { n -> IconButton(onClick = { vm.rating(if (c.rating == n) null else n) }) { Icon(if ((c.rating ?: 0) >= n) Icons.Default.Star else Icons.Outlined.StarBorder, "$n estrela(s)", tint = Color(0xFFFFC94D), modifier = Modifier.size(32.dp)) } } }
                        Text("Tags", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { t -> InputChip(selected = false, onClick = { vm.removeTag(t) }, label = { Text(t) }, trailingIcon = { Icon(Icons.Default.Close, "Remover tag $t", Modifier.size(16.dp)) }) }
                            AssistChip(onClick = { dialog = "tag" }, label = { Text("Nova tag") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                        }
                        if (memberOf.isNotEmpty()) {
                            Text("Coleções", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { allCollections.filter { it.collection.id in memberOf }.forEach { r -> AssistChip(onClick = { onOpenCollection(r.collection.id) }, label = { Text(r.collection.name) }, leadingIcon = { Icon(Icons.Default.Folder, null) }) } }
                        }
                    }
                }
            }
            if (bookmarks.isNotEmpty()) item {
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Marcadores (${bookmarks.size})", style = MaterialTheme.typography.titleMedium)
                        bookmarks.forEach { b -> BookmarkRow(b, onClick = { onRead(b.page) }, onEdit = { editingBookmark = b }, onDelete = { vm.deleteBookmark(b) }) }
                    }
                }
            }
            item {
                var notes by remember(c.notes) { mutableStateOf(c.notes ?: "") }
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Anotações pessoais", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(notes, { notes = it.take(4000) }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3, placeholder = { Text("Escreva suas impressões…") })
                        if (notes != (c.notes ?: "")) TextButton(onClick = { vm.notes(notes) }) { Text("Salvar anotação") }
                    }
                }
            }
            item {
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Informações do arquivo", style = MaterialTheme.typography.titleMedium)
                        InfoRow("Arquivo", c.fileName); InfoRow("Formato", c.formatLabel()); InfoRow("Pasta de origem", folderName ?: "—")
                        InfoRow("Páginas", if (c.pageCount > 0) "${c.pageCount}" else "—"); InfoRow("Tamanho", fmtSize(c.sizeBytes))
                        InfoRow("Adicionada em", fmtDate(c.addedAt)); InfoRow("Última leitura", fmtDate(c.lastReadAt))
                        InfoRow("Tempo total de leitura", fmtDuration(c.activeSeconds)); InfoRow("Sessões de leitura", "${c.sessionCount}")
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    OutlinedButton(onClick = { dialog = "remove" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.RemoveCircleOutline, null); Spacer(Modifier.width(8.dp)); Text("Remover da biblioteca") }
                    TextButton(onClick = { dialog = "deleteFile" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Apagar arquivo do aparelho…", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    when (dialog) {
        "tag" -> TextInputDialog("Nova tag", "Tag", onDismiss = { dialog = null }) { vm.addTag(it) }
        "page" -> TextInputDialog("Definir página (1 a ${c.pageCount})", "Página", c.currentPage.toString(), onDismiss = { dialog = null }) { it.trim().toIntOrNull()?.let(vm::setPage) }
        "total" -> TextInputDialog("Corrigir total de páginas", "Total", c.pageCount.toString(), onDismiss = { dialog = null }) { it.trim().toIntOrNull()?.let(vm::setTotal) }
        "bookmark" -> BookmarkDialog(null, "Página ${c.currentPage}", onDismiss = { dialog = null }) { n, col, note -> vm.addBookmark(c.currentPage, n, col, note); dialog = null }
        "reset" -> ConfirmDialog("Reiniciar progresso?", "A página atual, o histórico de sessões e o tempo de leitura desta HQ serão zerados. Marcadores e anotações são mantidos.", "Reiniciar", { vm.resetProgress() }, { dialog = null }, destructive = true)
        "history" -> HistoryDialog(sessions, onDismiss = { dialog = null })
        "edit" -> EditDialog(c, onDismiss = { dialog = null }) { t, s, i, v, y, a, p -> vm.edit(t, s, i, v, y, a, p); dialog = null }
        "collections" -> CollectionsDialog(allCollections.map { it.collection.id to it.collection.name }, memberOf, onToggle = { id, m -> vm.toggleCollection(id, m) }, onCreate = { vm.createCollectionAndAdd(it) }, onDismiss = { dialog = null })
        "cover" -> TextInputDialog("Capa: usar a página nº", "Página", onDismiss = { dialog = null }) { n -> n.trim().toIntOrNull()?.let { vm.setCoverPage(it) { ok -> message = if (ok) "Capa atualizada." else "Não foi possível usar essa página." } } }
        "remove" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text("Remover da biblioteca") },
            text = { Text("O arquivo original continua no aparelho. Deseja manter o progresso, marcadores e histórico desta HQ?") },
            confirmButton = { TextButton(onClick = { vm.removeFromLibrary(true); dialog = null; onBack() }) { Text("Remover e manter dados") } },
            dismissButton = { Row { TextButton(onClick = { vm.removeFromLibrary(false); dialog = null; onBack() }) { Text("Remover e apagar dados", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { dialog = null }) { Text("Cancelar") } } },
        )
        "deleteFile" -> ConfirmDialog("Apagar o arquivo do aparelho?", "\"${c.fileName}\" será excluído permanentemente do armazenamento. Esta ação não pode ser desfeita.", "Apagar arquivo", { vm.deleteFile { ok -> if (ok) onBack() else message = "Não foi possível apagar o arquivo (permissão negada pelo provedor)." } }, { dialog = null }, destructive = true)
    }
    editingBookmark?.let { b -> BookmarkDialog(b, "Página ${b.page}", onDismiss = { editingBookmark = null }) { n, col, note -> vm.updateBookmark(b.copy(name = n, color = col, note = note)); editingBookmark = null } }
}

@Composable private fun LocalContentColorOr(): Color = androidx.compose.material3.LocalContentColor.current

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) { Text(k, Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); Text(v, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun ProgressCard(total: Int, c: ComicEntity, eta: com.hqverse.core.Eta?, showEta: Boolean, onSetPage: () -> Unit, onTotal: () -> Unit, onReset: () -> Unit, onPause: () -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Seu progresso", style = MaterialTheme.typography.titleMedium)
            ProgressBar(c.percent() / 100f, height = 10.dp)
            Text(if (total > 0) "Página ${c.currentPage} de $total · ${c.percent()}% concluído" else "Páginas ainda não contadas", style = MaterialTheme.typography.bodyLarge)
            if (eta != null && c.readStatus() != ReadStatus.COMPLETED) {
                Text("${eta.remainingPages} páginas restantes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showEta) {
                    Text("Tempo restante estimado: ${formatMinutes(eta.minutes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ritmo médio: ${"%.1f".format(eta.pace.pagesPerMinute)} pág/min " + if (eta.pace.source == PaceSource.HISTORY) "(do seu histórico, ${eta.pace.sessionsUsed} sessões)" else "(ritmo padrão — ainda sem histórico suficiente)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("É uma estimativa e pode variar conforme a densidade das páginas.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
            }
            c.completedAt?.let { Text("Concluída em ${fmtDate(it)}", color = Color(0xFF3DDC97)) }
            FlowRowSimple {
                TextButton(onClick = onSetPage) { Text("Definir página") }
                TextButton(onClick = onTotal) { Text("Corrigir total") }
                if (c.readStatus() == ReadStatus.IN_PROGRESS || c.readStatus() == ReadStatus.PAUSED) TextButton(onClick = onPause) { Text(if (c.manuallyPaused) "Retomar" else "Pausar leitura") }
                TextButton(onClick = onReset) { Text("Reiniciar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FlowRowSimple(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }

@Composable
private fun HistoryDialog(sessions: List<com.hqverse.app.data.db.SessionEntity>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Histórico de leitura") },
        text = {
            if (sessions.isEmpty()) Text("Nenhuma sessão registrada ainda.") else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(sessions.size) { i -> val s = sessions[i]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(fmtDate(s.startMs), style = MaterialTheme.typography.titleMedium)
                        Text("${fmtDuration(s.activeSeconds)} ativos · ${s.newPages} páginas novas · ${s.pageVisits} trocas de página", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun EditDialog(c: ComicEntity, onDismiss: () -> Unit, onSave: (String, String?, Int?, Int?, Int?, String?, String?) -> Unit) {
    var title by remember { mutableStateOf(c.title) }; var series by remember { mutableStateOf(c.series ?: "") }
    var issue by remember { mutableStateOf(c.issue?.toString() ?: "") }; var volume by remember { mutableStateOf(c.volume?.toString() ?: "") }
    var year by remember { mutableStateOf(c.year?.toString() ?: "") }; var author by remember { mutableStateOf(c.author ?: "") }; var publisher by remember { mutableStateOf(c.publisher ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Editar informações") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(series, { series = it }, label = { Text("Série") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(issue, { issue = it.filter(Char::isDigit).take(4) }, label = { Text("Edição") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(volume, { volume = it.filter(Char::isDigit).take(3) }, label = { Text("Volume") }, singleLine = true, modifier = Modifier.weight(1f)) } }
                item { OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Ano") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(author, { author = it }, label = { Text("Autor") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(publisher, { publisher = it }, label = { Text("Editora") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, series.ifBlank { null }, issue.toIntOrNull(), volume.toIntOrNull(), year.toIntOrNull(), author.ifBlank { null }, publisher.ifBlank { null }) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun CollectionsDialog(all: List<Pair<Long, String>>, member: Set<Long>, onToggle: (Long, Boolean) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) { TextInputDialog("Nova coleção", "Nome", confirm = "Criar e adicionar", onDismiss = { creating = false }) { if (it.isNotBlank()) onCreate(it) }; return }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Adicionar à coleção") },
        text = {
            LazyColumn {
                items(all.size) { i -> val (id, name) = all[i]; val inside = id in member
                    Row(Modifier.fillMaxWidth().clickable { onToggle(id, inside) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(inside, { onToggle(id, inside) }); Text(name) } }
                item { TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Nova coleção") } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Pronto") } },
    )
}
