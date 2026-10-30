package com.hqverse.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hqverse.app.data.db.BookmarkEntity
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.ui.theme.LocalReduceMotion
import com.hqverse.core.ComicFormat
import com.hqverse.core.ProgressCalc
import com.hqverse.core.ReadFailure
import com.hqverse.core.ReadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CoverCache {
    val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }
}

@Composable
fun shimmerBrush(): Brush {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reduce) return Brush.linearGradient(listOf(base, base))
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    return Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x - 300f, 0f), end = androidx.compose.ui.geometry.Offset(x, 300f))
}

/** Capa vinda do cache em disco; mostra placeholder animado enquanto é gerada. */
@Composable
fun CoverImage(comic: ComicEntity, modifier: Modifier = Modifier, corner: Dp = 14.dp) {
    val path = comic.coverPath
    var image by remember(path) { mutableStateOf(path?.let { CoverCache.cache.get(it) }) }
    LaunchedEffect(path) {
        if (path != null && image == null) {
            image = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { CoverCache.cache.put(path, it) } }.getOrNull()
            }
        }
    }
    Box(modifier.aspectRatio(0.68f).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val img = image
        when {
            img != null -> Image(img, "Capa de ${comic.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            comic.errorCode != null -> Icon(Icons.Default.BrokenImage, null, Modifier.align(Alignment.Center).size(36.dp), tint = MaterialTheme.colorScheme.error)
            comic.missing -> Icon(Icons.Default.CloudOff, null, Modifier.align(Alignment.Center).size(36.dp), tint = MaterialTheme.colorScheme.outline)
            else -> Box(Modifier.fillMaxSize().background(shimmerBrush())) {
                Icon(Icons.Default.AutoStories, null, Modifier.align(Alignment.Center).size(32.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 5.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val reduce = LocalReduceMotion.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduce) snap() else tween(500), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(color))
    }
}

fun ComicEntity.readStatus(): ReadStatus = runCatching { ReadStatus.valueOf(status) }.getOrDefault(ReadStatus.NOT_STARTED)
fun ComicEntity.percent(): Int = if (readStatus() == ReadStatus.COMPLETED) 100 else ProgressCalc.percent(currentPage, pageCount)
fun ComicEntity.formatLabel(): String = runCatching { ComicFormat.valueOf(format).label }.getOrDefault(format)
fun ComicEntity.subtitle(): String = listOfNotNull(
    series?.takeIf { it != title }, issue?.let { "Edição $it" }, volume?.let { "Vol. $it" },
).joinToString(" · ")

@Composable
fun StatusBadge(comic: ComicEntity, modifier: Modifier = Modifier) {
    val (text, color) = when {
        comic.errorCode != null -> "Erro" to MaterialTheme.colorScheme.error
        comic.missing -> "Ausente" to MaterialTheme.colorScheme.outline
        else -> when (comic.readStatus()) {
            ReadStatus.COMPLETED -> "Concluída" to Color(0xFF3DDC97)
            ReadStatus.IN_PROGRESS -> "Em andamento" to MaterialTheme.colorScheme.primary
            ReadStatus.PAUSED -> "Pausada" to Color(0xFFFFB84D)
            ReadStatus.NOT_STARTED -> "Não iniciada" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Cartão em grade: capa grande, título, edição/série, progresso e estado. */
@Composable
fun ComicGridCard(comic: ComicEntity, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = "${comic.title}. ${comic.subtitle()}. ${comic.percent()}% lido" }
            .padding(4.dp),
    ) {
        Box {
            CoverImage(comic, Modifier.fillMaxWidth())
            if (comic.favorite) Icon(Icons.Default.Favorite, "Favorita", Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp), tint = Color(0xFFFF6B9A))
            if (comic.readStatus() == ReadStatus.COMPLETED) Icon(Icons.Default.CheckCircle, "Concluída", Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp), tint = Color(0xFF3DDC97))
        }
        Spacer(Modifier.height(8.dp))
        Text(comic.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp))
        comic.subtitle().takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(6.dp))
        ProgressBar(comic.percent() / 100f, height = 4.dp)
    }
}

@Composable
fun ComicListRow(comic: ComicEntity, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${comic.title}. ${comic.percent()}% lido" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(comic, Modifier.width(64.dp), corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(comic.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            val sub = listOf(comic.subtitle(), comic.formatLabel(), if (comic.pageCount > 0) "${comic.pageCount} págs" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(comic)
                Spacer(Modifier.width(8.dp))
                if (comic.favorite) Icon(Icons.Default.Favorite, "Favorita", Modifier.size(16.dp), tint = Color(0xFFFF6B9A))
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(comic.percent() / 100f, height = 4.dp)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

fun failureText(code: String?): String? = code?.let { runCatching { ReadFailure.valueOf(it).userMessage }.getOrNull() }

val BookmarkColors = listOf("#FF6B7A" to "Vermelho", "#FFB84D" to "Laranja", "#3DDC97" to "Verde", "#4F9CFF" to "Azul", "#B9B2FF" to "Violeta")
val BookmarkCategories = listOf("Melhor cena", "Revelação importante", "Voltar depois", "Cena favorita")

fun parseColor(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex ?: "#B9B2FF")) }.getOrDefault(Color(0xFFB9B2FF))

@Composable
fun BookmarkRow(b: BookmarkEntity, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Bookmark, null, tint = parseColor(b.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name ?: "Página ${b.page}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Página ${b.page}" + (b.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Editar marcador") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Excluir marcador") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkDialog(existing: BookmarkEntity?, pageLabel: String, onDismiss: () -> Unit, onSave: (name: String?, color: String?, note: String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: BookmarkColors[4].first) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (existing == null) "Novo marcador · $pageLabel" else "Editar marcador · $pageLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nome (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BookmarkCategories.forEach { cat -> AssistChip(onClick = { name = cat }, label = { Text(cat) }) }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Observação (opcional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BookmarkColors.forEach { (hex, label) ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(parseColor(hex)).border(if (color == hex) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = hex }.semantics { contentDescription = "Cor $label" },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.ifBlank { null }, color, note.ifBlank { null }) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String = "", confirm: String = "Salvar", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(v); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
