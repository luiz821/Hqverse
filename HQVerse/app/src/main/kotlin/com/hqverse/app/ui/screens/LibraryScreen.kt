package com.hqverse.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.data.settings.ViewMode
import com.hqverse.app.ui.LibraryViewModel
import com.hqverse.app.ui.components.*
import com.hqverse.core.*

private enum class Section(val label: String) {
    ALL("Todas"), RECENT("Recentes"), IN_PROGRESS("Em andamento"), UNREAD("Não lidas"), DONE("Concluídas"),
    FAVORITES("Favoritas"), WANT("Quero ler"), SERIES("Séries"), ERRORS("Com erro"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onPickFolder: () -> Unit, onOpen: (Long) -> Unit, onFolders: () -> Unit) {
    val items by vm.visible.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val errorCount by vm.errorCount.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(Section.ALL) }
    var searching by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ComicEntity?>(null) }

    fun pick(s: Section) {
        section = s
        val q = filter.query
        when (s) {
            Section.ALL -> vm.preset(); Section.RECENT -> { vm.preset(); vm.setSort(SortKey.ADDED, true) }
            Section.IN_PROGRESS -> vm.preset(setOf(ReadStatus.IN_PROGRESS, ReadStatus.PAUSED)); Section.UNREAD -> vm.preset(setOf(ReadStatus.NOT_STARTED))
            Section.DONE -> vm.preset(setOf(ReadStatus.COMPLETED)); Section.FAVORITES -> vm.preset(favorites = true)
            Section.WANT -> vm.preset(wantToRead = true); Section.SERIES -> vm.preset(); Section.ERRORS -> vm.preset(errors = true)
        }
        vm.update { it.copy(query = q) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Cabeçalho
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Minha biblioteca", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching; if (!searching) vm.update { it.copy(query = "") } }) { Icon(if (searching) Icons.Default.SearchOff else Icons.Default.Search, "Pesquisar") }
            IconButton(onClick = { vm.setViewMode(if (settings.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID) }) {
                Icon(if (settings.viewMode == ViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, if (settings.viewMode == ViewMode.GRID) "Mostrar em lista" else "Mostrar em grade")
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SortKey.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.label) }, onClick = { vm.setSort(k, if (settings.sortKey == k) !settings.sortDescending else (k == SortKey.ADDED || k == SortKey.LAST_READ || k == SortKey.PROGRESS)); sortMenu = false },
                            trailingIcon = { if (settings.sortKey == k) Icon(if (settings.sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, if (settings.sortDescending) "Decrescente" else "Crescente") },
                        )
                    }
                }
            }
            IconButton(onClick = { showFilters = true }) { BadgedBox(badge = { if (filterCount(filter) > 0) Badge { Text("${filterCount(filter)}") } }) { Icon(Icons.Default.FilterList, "Filtros") } }
        }
        AnimatedVisibility(searching) {
            OutlinedTextField(
                filter.query, { v -> vm.update { it.copy(query = v) } }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Título, série, edição, autor…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.query.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Default.Clear, "Limpar pesquisa") } },
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Section.entries.filter { it != Section.ERRORS || errorCount > 0 }) { s ->
                FilterChip(selected = section == s, onClick = { pick(s) }, label = { Text(if (s == Section.ERRORS) "Com erro ($errorCount)" else s.label) })
            }
        }
        if (scan.running) ScanBanner(vm, scan)
        scan.message?.takeIf { !scan.running }?.let { MessageBanner(it) { vm.dismissScanMessage() } }
        filter.series?.let { s -> AssistChip(onClick = { vm.update { it.copy(series = null) } }, label = { Text("Série: $s") }, trailingIcon = { Icon(Icons.Default.Close, "Remover filtro de série") }, modifier = Modifier.padding(horizontal = 16.dp)) }

        when {
            section == Section.SERIES && filter.series == null -> SeriesList(series, onPick = { vm.update { f -> f.copy(series = it) } })
            items.isEmpty() -> {
                val hasAny = folders.isNotEmpty()
                EmptyState(
                    if (!hasAny) Icons.Default.FolderOpen else Icons.Default.SearchOff,
                    if (!hasAny) "Nenhuma pasta selecionada" else if (scan.running) "Procurando HQs…" else "Nada por aqui",
                    if (!hasAny) "Selecione a pasta onde estão suas HQs." else "Nenhuma HQ corresponde aos filtros atuais. Verifique a busca, os filtros ou atualize a biblioteca.",
                    Modifier.weight(1f),
                    action = { if (!hasAny) Button(onClick = onPickFolder) { Text("Escolher pasta") } else if (filterCount(filter) > 0 || filter.query.isNotEmpty() || section != Section.ALL) OutlinedButton(onClick = { pick(Section.ALL); vm.update { LibraryFilter() } }) { Text("Limpar filtros") } },
                )
            }
            settings.viewMode == ViewMode.GRID -> {
                val cells = if (settings.gridColumns > 0) GridCells.Fixed(settings.gridColumns) else GridCells.Adaptive(124.dp)
                LazyVerticalGrid(cells, Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { c -> ComicGridCard(c, onClick = { onOpen(c.id) }, onLongClick = { actionsFor = c }, modifier = Modifier.animateItem()) }
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { c -> ComicListRow(c, onClick = { onOpen(c.id) }, onLongClick = { actionsFor = c }, modifier = Modifier.animateItem()) }
            }
        }
    }

    if (showFilters) FilterSheet(vm, onDismiss = { showFilters = false }, onFolders = { showFilters = false; onFolders() })
    actionsFor?.let { c -> QuickActions(c, vm, onDismiss = { actionsFor = null }, onOpen = { onOpen(c.id) }) }
}

private fun filterCount(f: LibraryFilter): Int = f.formats.size + (if (f.collectionId != null) 1 else 0) + (if (f.folderId != null) 1 else 0) + (if (f.tag != null) 1 else 0) + (if (f.showHidden) 1 else 0)

@Composable
private fun SeriesList(series: List<Pair<SeriesGroup, ComicEntity?>>, onPick: (String) -> Unit) {
    if (series.isEmpty()) { EmptyState(Icons.Default.Layers, "Nenhuma série identificada", "As séries são reconhecidas pelo nome dos arquivos (ex.: \"Batman 001.cbr\"). Você também pode editar a série de cada HQ."); return }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(series, key = { it.first.name }) { (g, cover) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onPick(g.name) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cover != null) CoverImage(cover, Modifier.width(56.dp), corner = 10.dp) else Box(Modifier.width(56.dp).aspectRatio(0.68f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.comics.size} edições · ${g.totalPages} páginas · ${g.completed} concluídas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp)); ProgressBar(if (g.comics.isEmpty()) 0f else g.completed.toFloat() / g.comics.size, height = 4.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(vm: LibraryViewModel, onDismiss: () -> Unit, onFolders: () -> Unit) {
    val f by vm.filter.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val cols by vm.collections.collectAsStateWithLifecycle()
    val tags by vm.allTags.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Filtros", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.update { LibraryFilter(query = it.query) } }) { Text("Limpar") } }
            Text("Formato", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicFormat.entries.forEach { fm -> FilterChip(f.formats.contains(fm), { vm.update { it.copy(formats = if (fm in it.formats) it.formats - fm else it.formats + fm) } }, label = { Text(fm.label) }) }
            }
            if (folders.size > 1) { Text("Pasta de origem", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { folders.forEach { fo -> FilterChip(f.folderId == fo.id, { vm.update { it.copy(folderId = if (it.folderId == fo.id) null else fo.id) } }, label = { Text(fo.name) }) } } }
            if (cols.isNotEmpty()) { Text("Coleção", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { cols.forEach { c -> FilterChip(f.collectionId == c.collection.id, { vm.update { it.copy(collectionId = if (it.collectionId == c.collection.id) null else c.collection.id) } }, label = { Text(c.collection.name) }) } } }
            if (tags.isNotEmpty()) { Text("Tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tags.forEach { t -> FilterChip(f.tag.equals(t, true), { vm.update { it.copy(tag = if (it.tag.equals(t, true)) null else t) } }, label = { Text(t) }) } } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Mostrar HQs ocultas", Modifier.weight(1f)); Switch(f.showHidden, { v -> vm.update { it.copy(showHidden = v) } }) }
            TextButton(onClick = onFolders) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Gerenciar pastas e arquivos com erro") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActions(c: ComicEntity, vm: LibraryViewModel, onDismiss: () -> Unit, onOpen: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(c.title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            ListItem(headlineContent = { Text("Ver detalhes") }, leadingContent = { Icon(Icons.Default.Info, null) }, modifier = Modifier.clickable { onDismiss(); onOpen() })
            ListItem(headlineContent = { Text(if (c.hidden) "Mostrar na biblioteca" else "Ocultar da biblioteca") }, leadingContent = { Icon(if (c.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) }, modifier = Modifier.clickable { vm.hide(c.id, !c.hidden); onDismiss() })
        }
    }
}

/** Pastas monitoradas, arquivos com erro e HQs ocultas. */
@Composable
fun FoldersScreen(vm: LibraryViewModel, onPickFolder: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val hidden by vm.hiddenList.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<com.hqverse.app.data.db.FolderEntity?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                Text("Pastas e arquivos", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (scan.running) item { ScanBanner(vm, scan) }
        scan.message?.takeIf { !scan.running }?.let { m -> item { MessageBanner(m) { vm.dismissScanMessage() } } }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickFolder) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Adicionar pasta") }
                OutlinedButton(onClick = { vm.rescanAll(context) }, enabled = !scan.running && folders.isNotEmpty()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Atualizar tudo") }
            }
            Text("O app só acessa as pastas que você autorizar. Remover uma pasta daqui não apaga nenhum arquivo.", Modifier.padding(20.dp, 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(folders, key = { it.id }) { f ->
            Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(f.lastScanAt?.let { "Última varredura: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "Ainda não varrida", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Incluir subpastas", Modifier.weight(1f)); Switch(f.includeSubfolders, { vm.setSubfolders(f, it) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Monitorar esta pasta", Modifier.weight(1f)); Switch(f.enabled, { vm.setFolderEnabled(f, it) }) }
                    Row { TextButton(onClick = { vm.rescan(context, f) }, enabled = !scan.running) { Text("Reindexar") }; TextButton(onClick = { removing = f }) { Text("Remover da biblioteca", color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
        if (problems.isNotEmpty()) {
            item { SectionTitle("Arquivos com problema (${problems.size})") }
            items(problems, key = { "p${it.id}" }) { c ->
                ListItem(
                    headlineContent = { Text(c.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(failureText(c.errorCode) ?: "Arquivo não encontrado na última varredura.") },
                    leadingContent = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    trailingContent = { if (c.errorCode != null) TextButton(onClick = { vm.retry(c) }) { Text("Tentar") } },
                )
            }
        }
        if (hidden.isNotEmpty()) {
            item { SectionTitle("HQs ocultas (${hidden.size})") }
            items(hidden, key = { "h${it.id}" }) { c ->
                ListItem(headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingContent = { TextButton(onClick = { vm.hide(c.id, false) }) { Text("Mostrar") } })
            }
        }
        item {
            SectionTitle("Pastas ignoradas")
            var text by remember(settings.ignoredFolderNames) { mutableStateOf(settings.ignoredFolderNames) }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Nomes de subpastas a ignorar (separados por vírgula)") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.setIgnored(text) }) { Text("Salvar") }
            }
        }
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null }, title = { Text("Remover \"${f.name}\"?") },
            text = { Text("Os arquivos originais NÃO serão apagados. Escolha o que fazer com o progresso, marcadores e histórico das HQs desta pasta.") },
            confirmButton = { TextButton(onClick = { vm.removeFolder(f, true); removing = null }) { Text("Remover e preservar dados") } },
            dismissButton = { Row { TextButton(onClick = { vm.removeFolder(f, false); removing = null }) { Text("Remover e apagar dados", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { removing = null }) { Text("Cancelar") } } },
        )
    }
}
