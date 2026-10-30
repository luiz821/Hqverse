package com.hqverse.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hqverse.app.data.db.BookmarkEntity
import com.hqverse.app.data.reader.PageLoader
import com.hqverse.app.data.settings.*
import com.hqverse.app.ui.ReaderState
import com.hqverse.app.ui.ReaderViewModel
import com.hqverse.app.ui.theme.LocalReduceMotion
import com.hqverse.core.ComicReadException
import com.hqverse.core.PaceSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** Página lógica exibida: 1 ou 2 índices (base 0), na ordem visual da esquerda para a direita. */
private data class Spread(val pages: List<Int>)

private fun buildSpreads(count: Int, double: Boolean, rtl: Boolean): List<Spread> {
    if (!double) return List(count) { Spread(listOf(it)) }
    val out = mutableListOf(Spread(listOf(0))) // capa sozinha
    var i = 1
    while (i < count) {
        val pair = if (i + 1 < count) listOf(i, i + 1) else listOf(i)
        out += Spread(if (rtl) pair.reversed() else pair)
        i += 2
    }
    return out
}

@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val widthPx = LocalConfiguration.current.let { with(LocalDensity.current) { it.screenWidthDp.dp.roundToPx() } }
    var controls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    LaunchedEffect(Unit) { vm.open(widthPx.coerceAtLeast(720)) }

    // Ciclo de vida: segundo plano pausa o contador e salva.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) vm.onBackground()
            if (e == Lifecycle.Event.ON_START) vm.onForeground()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Janela: tela cheia, brilho, tela ligada, orientação.
    DisposableEffect(activity, settings.brightness, settings.keepScreenOn, settings.orientation) {
        val w = activity?.window
        val oldOrientation = activity?.requestedOrientation
        if (w != null) {
            val lp = w.attributes
            lp.screenBrightness = if (settings.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else settings.brightness.coerceIn(0.02f, 1f)
            w.attributes = lp
            if (settings.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activity?.requestedOrientation = when (settings.orientation) {
            OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; w.attributes = lp
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity, controls) {
        val w = activity?.window
        if (w != null) {
            val c = WindowInsetsControllerCompat(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose { activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is ReaderState.Failed -> ReaderError(s.failure.userMessage, onBack)
            is ReaderState.Ready -> ReaderContent(vm, s, settings, controls, onToggleControls = { controls = !controls }, onHideControls = { controls = false }, onOpenSheet = { sheet = it }, onBack = onBack, title = title)
        }
        CompletedBadge(vm, settings.haptics, Modifier.align(Alignment.Center))
    }

    when (sheet) {
        Sheet.BOOKMARKS -> BookmarksSheet(vm, onDismiss = { sheet = null })
        Sheet.OPTIONS -> OptionsSheet(vm, settings, onDismiss = { sheet = null })
        null -> Unit
    }
}

private enum class Sheet { BOOKMARKS, OPTIONS }

@Composable
private fun ReaderError(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B7A), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Não foi possível abrir esta HQ", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFFCCCEDD), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Voltar à biblioteca") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    vm: ReaderViewModel, s: ReaderState.Ready, settings: AppSettings, controls: Boolean,
    onToggleControls: () -> Unit, onHideControls: () -> Unit, onOpenSheet: (Sheet) -> Unit, onBack: () -> Unit, title: String,
) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val rtl = settings.direction == ReadingDirection.RTL
    val mode = if (settings.readerMode == ReaderMode.DOUBLE && !landscape) ReaderMode.SINGLE else settings.readerMode
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val animate = settings.pageAnimation && !reduce
    val current by vm.currentIndex.collectAsStateWithLifecycle()
    val eta by vm.eta.collectAsStateWithLifecycle()
    val timerPaused by vm.timerPaused.collectAsStateWithLifecycle()

    // Auto-ocultar controles.
    LaunchedEffect(controls, current) { if (controls) { delay(3500); onHideControls() } }

    var goTo by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    LaunchedEffect(Unit) { vm.jump.collect { goTo?.invoke(it) } }
    var turn by remember { mutableStateOf<((Int) -> Unit)?>(null) } // +1 próxima, -1 anterior (em termos de leitura)

    Box(Modifier.fillMaxSize()) {
        if (mode == ReaderMode.CONTINUOUS) {
            ContinuousReader(vm, s, current, onToggleControls) { goTo = it }
        } else {
            val spreads = remember(s.pageCount, mode, rtl) { buildSpreads(s.pageCount, mode == ReaderMode.DOUBLE, rtl) }
            val pagerState = rememberPagerState(initialPage = spreads.indexOfFirst { s.startIndex in it.pages }.coerceAtLeast(0)) { spreads.size }
            var zoomed by remember { mutableStateOf(false) }
            LaunchedEffect(pagerState, spreads) {
                snapshotFlow { pagerState.settledPage }.collect { p ->
                    val first = spreads[p].pages.minOrNull() ?: 0
                    if (first != vm.currentIndex.value && vm.currentIndex.value !in spreads[p].pages) {
                        if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.onPage(first)
                    }
                    zoomed = false
                }
            }
            LaunchedEffect(Unit) { goTo = { idx -> scope.launch { val t = spreads.indexOfFirst { idx in it.pages }.coerceAtLeast(0); if (animate) pagerState.animateScrollToPage(t) else pagerState.scrollToPage(t) } } }
            LaunchedEffect(Unit) {
                // O índice do pager segue a ordem de leitura (mesmo em RTL, onde só o layout é invertido).
                turn = { dir ->
                    val target = (pagerState.currentPage + dir).coerceIn(0, spreads.lastIndex)
                    scope.launch { if (animate) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target) }
                }
            }
            HorizontalPager(
                state = pagerState, reverseLayout = rtl, userScrollEnabled = !zoomed, beyondViewportPageCount = 1,
                key = { it }, modifier = Modifier.fillMaxSize(),
            ) { page ->
                val sp = spreads[page]
                Row(Modifier.fillMaxSize()) {
                    for (idx in sp.pages) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ZoomablePage(
                                loader = s.loader, index = idx, fit = settings.fitMode, active = page == pagerState.settledPage,
                                onZoomChanged = { if (page == pagerState.settledPage) zoomed = it },
                                onTapZone = { zone ->
                                    when (zone) { TapZone.CENTER -> onToggleControls(); TapZone.LEFT -> turn?.invoke(if (rtl) 1 else -1); TapZone.RIGHT -> turn?.invoke(if (rtl) -1 else 1) }
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(tween(if (reduce) 0 else 180)), exit = fadeOut(tween(if (reduce) 0 else 180)), modifier = Modifier.align(Alignment.TopCenter)) {
            TopControls(title, timerPaused, onBack, onToggleTimer = { vm.toggleTimer() }, onBookmarks = { onOpenSheet(Sheet.BOOKMARKS) }, onOptions = { onOpenSheet(Sheet.OPTIONS) })
        }
        AnimatedVisibility(controls, enter = fadeIn(tween(if (reduce) 0 else 180)), exit = fadeOut(tween(if (reduce) 0 else 180)), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomControls(current + 1, s.pageCount, rtl, settings, eta, onSeek = { goTo?.invoke(it - 1); vm.interaction() })
        }
    }
}

@Composable
private fun TopControls(title: String, timerPaused: Boolean, onBack: () -> Unit, onToggleTimer: () -> Unit, onBookmarks: () -> Unit, onOptions: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ControlsBg).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar à biblioteca", tint = Color.White) }
        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleTimer, Modifier.size(48.dp)) {
            Icon(if (timerPaused) Icons.Default.PlayArrow else Icons.Default.Pause, if (timerPaused) "Retomar contador de tempo" else "Pausar contador de tempo", tint = Color.White)
        }
        IconButton(onClick = onBookmarks, Modifier.size(48.dp)) { Icon(Icons.Default.Bookmarks, "Marcadores", tint = Color.White) }
        IconButton(onClick = onOptions, Modifier.size(48.dp)) { Icon(Icons.Default.Tune, "Opções do leitor", tint = Color.White) }
    }
}

private val ControlsBg = Color(0xCC0D0F1A)

@Composable
private fun BottomControls(page: Int, total: Int, rtl: Boolean, settings: AppSettings, eta: com.hqverse.core.Eta?, onSeek: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(ControlsBg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        val shown = dragging?.toInt() ?: page
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Página $shown de $total", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text("${com.hqverse.core.ProgressCalc.percent(shown, total)}%", color = Color(0xFFCCCEDD), style = MaterialTheme.typography.labelMedium)
        }
        // RTL: o controle é espelhado para acompanhar a direção de leitura.
        Box(Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
            Slider(
                value = (dragging ?: page.toFloat()), onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeek(it.toInt()) }; dragging = null },
                valueRange = 1f..max(2f, total.toFloat()), enabled = total > 1,
                modifier = Modifier.semantics { contentDescription = "Navegar entre páginas" },
            )
        }
        if (settings.showEta && eta != null) {
            val src = if (eta.pace.source == PaceSource.HISTORY) "seu ritmo médio" else "ritmo padrão"
            Text("${eta.remainingPages} páginas restantes · cerca de ${formatMinutes(eta.minutes)} (estimativa, $src: ${"%.1f".format(eta.pace.pagesPerMinute)} pág/min)",
                color = Color(0xFFCCCEDD), style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun formatMinutes(min: Int): String = when {
    min <= 0 -> "menos de 1 min"
    min < 60 -> "$min min"
    else -> "${min / 60} h ${min % 60} min"
}

private enum class TapZone { LEFT, CENTER, RIGHT }

/** Página com zoom por pinça, arrastar, toque duplo e ajuste (tela, largura, altura, original). */
@Composable
private fun ZoomablePage(loader: PageLoader, index: Int, fit: FitMode, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTapZone: (TapZone) -> Unit) {
    var bitmap by remember(index) { mutableStateOf(loader.peek(index)) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var attempt by remember(index) { mutableIntStateOf(0) }
    LaunchedEffect(index, attempt) {
        if (bitmap == null) {
            try { bitmap = loader.get(index); error = null }
            catch (e: ComicReadException) { error = e.failure.userMessage }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { error = "Não foi possível exibir esta página." }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(error!!, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { error = null; attempt++ }) { Text("Tentar de novo") }
            } else CircularProgressIndicator(color = Color(0xFFB9B2FF), modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        return
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(index, fit) { mutableFloatStateOf(1f) }
    var offset by remember(index, fit) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = LocalDensity.current

    // Tamanho-base da imagem exibida (antes do zoom), conforme o modo de ajuste.
    val vw = viewport.width.toFloat().coerceAtLeast(1f); val vh = viewport.height.toFloat().coerceAtLeast(1f)
    val ar = bmp.width.toFloat() / bmp.height
    val (baseW, baseH) = when (fit) {
        FitMode.SCREEN -> if (vw / vh > ar) (vh * ar to vh) else (vw to vw / ar)
        FitMode.WIDTH -> vw to vw / ar
        FitMode.HEIGHT -> vh * ar to vh
        FitMode.ORIGINAL -> bmp.width.toFloat() to bmp.height.toFloat()
    }

    fun clamp(o: androidx.compose.ui.geometry.Offset, sc: Float): androidx.compose.ui.geometry.Offset {
        val maxX = max(0f, (baseW * sc - vw) / 2f); val maxY = max(0f, (baseH * sc - vh) / 2f)
        return androidx.compose.ui.geometry.Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    // Em "largura" a página alta começa no topo; em "altura" larga começa à esquerda.
    LaunchedEffect(viewport, fit, index) {
        if (viewport != IntSize.Zero) offset = clamp(androidx.compose.ui.geometry.Offset(max(0f, (baseW - vw) / 2f), max(0f, (baseH - vh) / 2f)), 1f)
    }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.02f) } // ampliada: o pager para de trocar de página

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).onSizeChanged { viewport = it }
            .pointerInput(index, fit, baseW, baseH, vw, vh) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1.05f) { scale = 1f; offset = clamp(androidx.compose.ui.geometry.Offset.Zero, 1f) }
                        else { scale = 2.5f; offset = clamp(androidx.compose.ui.geometry.Offset((vw / 2f - p.x) * 1.5f, (vh / 2f - p.y) * 1.5f), 2.5f) }
                    },
                    onTap = { p -> onTapZone(if (p.x < vw * 0.25f) TapZone.LEFT else if (p.x > vw * 0.75f) TapZone.RIGHT else TapZone.CENTER) },
                )
            }
            .pointerInput(index, fit, baseW, baseH, vw, vh) {
                // Só consome gestos com 2+ dedos ou quando já ampliado/pan possível: assim o pager continua deslizando.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.count { it.pressed } > 1
                        val pan0 = ev.calculatePan()
                        // Ampliada: qualquer arrasto move a página. Não ampliada: só consome arrasto vertical
                        // quando a página é mais alta que a tela (ajuste à largura); o horizontal continua com o pager.
                        val panAllowed = scale > 1.02f || (baseH > vh + 1f && kotlin.math.abs(pan0.y) > kotlin.math.abs(pan0.x))
                        if (multi || panAllowed) {
                            val zoom = ev.calculateZoom(); val pan = pan0
                            val ns = (scale * zoom).coerceIn(1f, 5f)
                            scale = ns
                            offset = clamp(offset + pan, ns)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = "Página ${index + 1}",
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            modifier = Modifier.requiredSize(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}

/** Rolagem vertical contínua: cada página é carregada sob demanda e a memória é liberada ao se afastar. */
@Composable
private fun ContinuousReader(vm: ReaderViewModel, s: ReaderState.Ready, current: Int, onToggle: () -> Unit, registerGoTo: (((Int) -> Unit)) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = s.startIndex)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { registerGoTo { idx -> scope.launch { list.scrollToItem(idx) } } }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { if (it != vm.currentIndex.value) vm.onPage(it) }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() }) {
        itemsIndexed(List(s.pageCount) { it }, key = { _, v -> v }) { _, idx ->
            var bmp by remember(idx) { mutableStateOf(s.loader.peek(idx)) }
            var failed by remember(idx) { mutableStateOf(false) }
            LaunchedEffect(idx) { if (bmp == null) runCatching { s.loader.get(idx) }.onSuccess { bmp = it }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failed = true } }
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), "Página ${idx + 1}", Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().aspectRatio(0.66f), contentAlignment = Alignment.Center) {
                if (failed) Text("Página ${idx + 1} indisponível", color = Color.White) else CircularProgressIndicator(color = Color(0xFFB9B2FF), modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun CompletedBadge(vm: ReaderViewModel, haptics: Boolean, modifier: Modifier) {
    val done by vm.justCompleted.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(done) { if (done) { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress); delay(2600); vm.dismissCompleted() } }
    AnimatedVisibility(done, modifier = modifier, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 12.dp) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Leitura concluída!", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---------------- folhas ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(vm: ReaderViewModel, s: AppSettings, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Opções do leitor", style = MaterialTheme.typography.titleLarge)
            ChipRow("Modo", ReaderMode.entries, s.readerMode, { it.label }) { vm.setReaderMode(it) }
            if (s.readerMode == ReaderMode.DOUBLE) Text("Página dupla é usada com o aparelho na horizontal.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow("Direção", ReadingDirection.entries, s.direction, { it.label }) { vm.setDirection(it) }
            ChipRow("Ajuste", FitMode.entries, s.fitMode, { it.label }) { vm.setFit(it) }
            ChipRow("Orientação", OrientationLock.entries, s.orientation, { it.label }) { vm.setOrientation(it) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Brilho", Modifier.weight(1f)); TextButton(onClick = { vm.setBrightness(-1f) }) { Text(if (s.brightness < 0) "Sistema ✓" else "Usar do sistema") }
                }
                Slider(value = if (s.brightness < 0) 0.5f else s.brightness, onValueChange = { vm.setBrightness(it) }, valueRange = 0.02f..1f, modifier = Modifier.semantics { contentDescription = "Brilho da tela" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Manter a tela ligada", Modifier.weight(1f)); Switch(s.keepScreenOn, { vm.setKeepScreenOn(it) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val items by vm.bookmarks.collectAsStateWithLifecycle()
    val current by vm.currentIndex.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Marcadores", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { adding = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text("Adicionar marcador na página ${current + 1}") }
            if (items.isEmpty()) Text("Nenhum marcador nesta HQ ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(items, key = { _, b -> b.id }) { _, b ->
                    BookmarkRow(b, onClick = { vm.requestJump(b.page - 1); onDismiss() }, onEdit = { editing = b }, onDelete = { vm.deleteBookmark(b) })
                }
            }
        }
    }
    if (adding) BookmarkDialog(null, "Página ${current + 1}", onDismiss = { adding = false }) { n, c, note -> vm.addBookmark(n, c, note); adding = false }
    editing?.let { b -> BookmarkDialog(b, "Página ${b.page}", onDismiss = { editing = null }) { n, c, note -> vm.updateBookmark(b.copy(name = n, color = c, note = note)); editing = null } }
}
