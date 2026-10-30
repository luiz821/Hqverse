package com.hqverse.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.ui.CollectionDetailViewModel
import com.hqverse.app.ui.CollectionsViewModel
import com.hqverse.app.ui.components.*

@Composable
fun CollectionsScreen(vm: CollectionsViewModel, onOpen: (Long) -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Coleções", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Nova") }
            Spacer(Modifier.width(12.dp))
        }
        if (rows.isEmpty()) EmptyState(Icons.Default.Folder, "Nenhuma coleção ainda", "Agrupe HQs por editora, personagem ou tema (Marvel, DC, Leituras de 2026…). Os arquivos originais nunca são movidos ou copiados.", action = { Button(onClick = { creating = true }) { Text("Criar coleção") } })
        else LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.collection.id }) { r ->
                Surface(Modifier.fillMaxWidth().clickable { onOpen(r.collection.id) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(r.collection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${r.itemCount} edições", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { deleting = r.collection.id }) { Icon(Icons.Default.Delete, "Excluir coleção ${r.collection.name}") }
                    }
                }
            }
        }
    }
    if (creating) TextInputDialog("Nova coleção", "Nome", confirm = "Criar", onDismiss = { creating = false }) { if (it.isNotBlank()) vm.create(it) }
    deleting?.let { id -> ConfirmDialog("Excluir coleção?", "Apenas a coleção é removida. As HQs e os arquivos continuam intactos.", "Excluir", { vm.delete(id) }, { deleting = null }, destructive = true) }
}

@Composable
fun CollectionDetailScreen(vm: CollectionDetailViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val all by vm.allComics.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val col = ui.collection
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
            Text(col?.name ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { dialog = "rename" }) { Icon(Icons.Default.Edit, "Renomear coleção") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${ui.summary.issues} edições · ${ui.summary.pages} páginas · ${ui.summary.completed} concluídas", style = MaterialTheme.typography.titleMedium)
                        ProgressBar(ui.summary.percentCompleted / 100f, height = 8.dp)
                        Text("${ui.summary.percentCompleted}% da coleção concluída (considerando só as edições que você possui)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Nunca presume que a coleção está completa: só informa faltas se o usuário definiu a sequência.
                        val missing = ui.missing
                        when {
                            col?.expectedIssues == null -> Text("Para ver edições ausentes, informe a sequência esperada (ex.: 1-12).", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            missing.isNullOrEmpty() -> Text("Nenhuma edição ausente em ${col.expectedIssues}.", color = androidx.compose.ui.graphics.Color(0xFF3DDC97))
                            else -> Text("Faltam as edições: ${missing.joinToString(", ")}", color = MaterialTheme.colorScheme.error)
                        }
                        Row { TextButton(onClick = { dialog = "expected" }) { Text("Sequência esperada") }; TextButton(onClick = { dialog = "add" }) { Text("Adicionar HQs") } }
                    }
                }
            }
            if (ui.items.isEmpty()) item { EmptyState(Icons.Default.LibraryAdd, "Coleção vazia", "Toque em \"Adicionar HQs\" para escolher da sua biblioteca.") }
            items(ui.items, key = { it.first.comicId }) { (item, comic) ->
                if (comic != null) Column {
                    ComicListRow(comic, onClick = { onOpen(comic.id) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.move(comic.id, -1) }) { Icon(Icons.Default.ArrowUpward, "Mover para cima na ordem de leitura") }
                        IconButton(onClick = { vm.move(comic.id, 1) }) { Icon(Icons.Default.ArrowDownward, "Mover para baixo na ordem de leitura") }
                        FilterChip(item.owned, { vm.setOwned(comic.id, !item.owned) }, label = { Text(if (item.owned) "Possuo" else "Não possuo") })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.remove(comic.id) }) { Text("Tirar da coleção") }
                    }
                }
            }
        }
    }
    when (dialog) {
        "rename" -> TextInputDialog("Renomear coleção", "Nome", col?.name ?: "", onDismiss = { dialog = null }) { vm.rename(it) }
        "expected" -> TextInputDialog("Sequência esperada", "Ex.: 1-12 ou 1-5,8,10", col?.expectedIssues ?: "", onDismiss = { dialog = null }) { vm.setExpected(it) { invalid = true } }
        "add" -> {
            val inside = ui.items.map { it.first.comicId }.toSet()
            AlertDialog(
                onDismissRequest = { dialog = null }, title = { Text("Adicionar à coleção") },
                text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(all.filter { !it.hidden && it.id !in inside }.sortedBy { it.title.lowercase() }, key = { it.id }) { c ->
                    ListItem(headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(c.subtitle()) }, trailingContent = { IconButton(onClick = { vm.add(c.id) }) { Icon(Icons.Default.AddCircle, "Adicionar ${c.title}") } }) } } },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Pronto") } },
            )
        }
    }
    if (invalid) AlertDialog(onDismissRequest = { invalid = false }, title = { Text("Sequência inválida") }, text = { Text("Use o formato 1-12 ou 1-5,8,10 (números positivos, em ordem crescente).") }, confirmButton = { TextButton(onClick = { invalid = false }) { Text("OK") } })
}
