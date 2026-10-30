package com.hqverse.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.data.repo.StatsUi
import com.hqverse.app.ui.StatsViewModel
import com.hqverse.app.ui.components.*
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(vm: StatsViewModel, onOpen: (Long) -> Unit) {
    val s by vm.stats.collectAsStateWithLifecycle()
    val st = s
    if (st == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Estatísticas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (st.totals.comics == 0) { item { EmptyState(Icons.Default.BarChart, "Ainda sem dados", "Adicione uma pasta e leia algumas HQs. As estatísticas usam apenas o que o app registra no seu aparelho.") }; return@LazyColumn }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("HQs", "${st.totals.comics}", Modifier.weight(1f)); Stat("Páginas na biblioteca", "${st.totals.availablePages}", Modifier.weight(1f))
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Stat("Concluídas", "${st.totals.completed}", Modifier.weight(1f)); Stat("Em andamento", "${st.totals.inProgress}", Modifier.weight(1f)); Stat("Não iniciadas", "${st.totals.notStarted}", Modifier.weight(1f)) } }
        item {
            Panel("Leitura") {
                Text("${st.pagesRead} páginas únicas lidas (${st.overallPercent}% da biblioteca)", style = MaterialTheme.typography.titleMedium)
                ProgressBar(st.overallPercent / 100f, height = 8.dp)
                Text("Revisitar uma página não a conta de novo. Tempo acumulado: ${fmtDuration(st.totalSeconds)} em ${st.sessions} sessões.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Páginas / sessão", "%.1f".format(st.avgPagesPerSession), Modifier.weight(1f)); Stat("Tempo / sessão", fmtDuration(st.avgSecondsPerSession.toLong()), Modifier.weight(1f))
                }
            }
        }
        item {
            Panel("Sequência de dias") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Atual", "${st.currentStreak} dias", Modifier.weight(1f)); Stat("Recorde", "${st.longestStreak} dias", Modifier.weight(1f))
                    Stat("Dia mais ativo", st.mostActiveDay?.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")) ?: "—", Modifier.weight(1f))
                }
            }
        }
        item { Panel("Páginas novas por semana") { BarChart(st.weekly.map { it.first.dayOfMonth.toString() + "/" + it.first.monthValue to it.second }, "semana") } }
        item { Panel("Páginas novas por mês") { BarChart(st.monthly.map { it.first.month.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")) to it.second }, "mês") } }
        item { Panel("Calendário de leitura") { ReadingCalendar(st.readingDays) } }
        if (st.byFormat.isNotEmpty()) item {
            Panel("Biblioteca por formato") {
                val total = st.byFormat.values.sum().coerceAtLeast(1)
                st.byFormat.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(k, Modifier.width(120.dp)); ProgressBar(v.toFloat() / total, Modifier.weight(1f), 8.dp); Text("  $v", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (st.topCollections.isNotEmpty()) item { Panel("Coleções mais lidas (sessões)") { st.topCollections.forEach { (n, c) -> Row { Text(n, Modifier.weight(1f)); Text("$c") } } } }
        if (st.completedHistory.isNotEmpty()) item {
            Panel("Histórico de concluídas") {
                st.completedHistory.forEach { (t, ms) -> Row { Text(t, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms)), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); content() }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BarChart(data: List<Pair<String, Int>>, unit: String) {
    val max = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val summary = data.joinToString("; ") { "${it.first}: ${it.second} páginas" }
    Row(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Gráfico por $unit. $summary" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        data.forEach { (label, v) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("$v", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().fillMaxHeight(if (v == 0) 0.02f else 0.75f * v / max).clip(RoundedCornerShape(6.dp)).background(if (v == 0) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReadingCalendar(days: Set<LocalDate>) {
    val today = LocalDate.now()
    val start = today.minusDays(((today.dayOfWeek.value - 1) + 7 * 11).toLong()) // 12 semanas, começando na segunda
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        for (w in 0 until 12) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 0 until 7) {
                val date = start.plusDays((w * 7 + d).toLong())
                val read = date in days
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(when { date.isAfter(today) -> Color.Transparent; read -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceContainerHighest })
                    .semantics { if (!date.isAfter(today)) contentDescription = "$date: ${if (read) "leu" else "sem leitura"}" })
            }
        }
        Text("${days.count { !it.isBefore(start) }} dias com leitura nas últimas 12 semanas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
