package com.hqverse.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hqverse.core.SortKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode(val label: String) { SYSTEM("Automático"), DARK("Escuro"), LIGHT("Claro") }
enum class Accent(val label: String, val argb: Long) {
    VIOLET("Violeta", 0xFF8B7CFF), BLUE("Azul", 0xFF4F9CFF), TEAL("Turquesa", 0xFF2FD3B5),
    PINK("Rosa", 0xFFFF6FB5), ORANGE("Laranja", 0xFFFF9F4A),
}
enum class ViewMode(val label: String) { GRID("Grade"), LIST("Lista") }
enum class ReaderMode(val label: String) { SINGLE("Página única"), DOUBLE("Página dupla"), CONTINUOUS("Rolagem vertical") }
enum class ReadingDirection(val label: String) { LTR("Esquerda → direita"), RTL("Direita → esquerda (mangá)") }
enum class FitMode(val label: String) { SCREEN("Ajuste automático"), WIDTH("Ajustar à largura"), HEIGHT("Ajustar à altura"), ORIGINAL("Tamanho original") }
enum class OrientationLock(val label: String) { AUTO("Automática"), PORTRAIT("Vertical"), LANDSCAPE("Horizontal") }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Accent = Accent.VIOLET,
    val viewMode: ViewMode = ViewMode.GRID,
    /** 0 = automático conforme a largura da tela. */
    val gridColumns: Int = 0,
    val sortKey: SortKey = SortKey.NAME,
    val sortDescending: Boolean = false,
    val ignoredFolderNames: String = ".thumbnails,thumbnails,@eaDir",
    val autoScanOnStart: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.SINGLE,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val fitMode: FitMode = FitMode.SCREEN,
    val pageAnimation: Boolean = true,
    /** -1 = brilho do sistema. */
    val brightness: Float = -1f,
    val orientation: OrientationLock = OrientationLock.AUTO,
    val keepScreenOn: Boolean = true,
    val autoSave: Boolean = true,
    val saveIntervalSec: Int = 15,
    val defaultPagesPerMinute: Float = 1.5f,
    val showEta: Boolean = true,
    val trackTime: Boolean = true,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    val completionPercent: Int = 80,
    val endTolerancePages: Int = 1,
    val idleCapSec: Int = 120,
    val cacheLimitMb: Int = 512,
)

/** Chaves de preferência. [all] é usado para exportar/importar as configurações no backup. */
private object K {
    val theme = stringPreferencesKey("theme")
    val accent = stringPreferencesKey("accent")
    val viewMode = stringPreferencesKey("viewMode")
    val gridColumns = intPreferencesKey("gridColumns")
    val sortKey = stringPreferencesKey("sortKey")
    val sortDescending = booleanPreferencesKey("sortDescending")
    val ignoredFolderNames = stringPreferencesKey("ignoredFolderNames")
    val autoScanOnStart = booleanPreferencesKey("autoScanOnStart")
    val readerMode = stringPreferencesKey("readerMode")
    val direction = stringPreferencesKey("direction")
    val fitMode = stringPreferencesKey("fitMode")
    val pageAnimation = booleanPreferencesKey("pageAnimation")
    val brightness = floatPreferencesKey("brightness")
    val orientation = stringPreferencesKey("orientation")
    val keepScreenOn = booleanPreferencesKey("keepScreenOn")
    val autoSave = booleanPreferencesKey("autoSave")
    val saveIntervalSec = intPreferencesKey("saveIntervalSec")
    val defaultPpm = floatPreferencesKey("defaultPpm")
    val showEta = booleanPreferencesKey("showEta")
    val trackTime = booleanPreferencesKey("trackTime")
    val haptics = booleanPreferencesKey("haptics")
    val reduceMotion = booleanPreferencesKey("reduceMotion")
    val completionPercent = intPreferencesKey("completionPercent")
    val endTolerance = intPreferencesKey("endTolerance")
    val idleCapSec = intPreferencesKey("idleCapSec")
    val cacheLimitMb = intPreferencesKey("cacheLimitMb")

    val all: List<Preferences.Key<*>> = listOf(
        theme, accent, viewMode, gridColumns, sortKey, sortDescending, ignoredFolderNames, autoScanOnStart,
        readerMode, direction, fitMode, pageAnimation, brightness, orientation, keepScreenOn, autoSave,
        saveIntervalSec, defaultPpm, showEta, trackTime, haptics, reduceMotion, completionPercent,
        endTolerance, idleCapSec, cacheLimitMb,
    )
}

private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
    this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p.enum(K.theme, d.theme), accent = p.enum(K.accent, d.accent),
            viewMode = p.enum(K.viewMode, d.viewMode), gridColumns = (p[K.gridColumns] ?: d.gridColumns).coerceIn(0, 6),
            sortKey = p.enum(K.sortKey, d.sortKey), sortDescending = p[K.sortDescending] ?: d.sortDescending,
            ignoredFolderNames = p[K.ignoredFolderNames] ?: d.ignoredFolderNames,
            autoScanOnStart = p[K.autoScanOnStart] ?: d.autoScanOnStart,
            readerMode = p.enum(K.readerMode, d.readerMode), direction = p.enum(K.direction, d.direction),
            fitMode = p.enum(K.fitMode, d.fitMode), pageAnimation = p[K.pageAnimation] ?: d.pageAnimation,
            brightness = p[K.brightness] ?: d.brightness, orientation = p.enum(K.orientation, d.orientation),
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn, autoSave = p[K.autoSave] ?: d.autoSave,
            saveIntervalSec = (p[K.saveIntervalSec] ?: d.saveIntervalSec).coerceIn(5, 120),
            defaultPagesPerMinute = (p[K.defaultPpm] ?: d.defaultPagesPerMinute).coerceIn(0.1f, 30f),
            showEta = p[K.showEta] ?: d.showEta, trackTime = p[K.trackTime] ?: d.trackTime,
            haptics = p[K.haptics] ?: d.haptics, reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            completionPercent = (p[K.completionPercent] ?: d.completionPercent).coerceIn(50, 100),
            endTolerancePages = (p[K.endTolerance] ?: d.endTolerancePages).coerceIn(0, 10),
            idleCapSec = (p[K.idleCapSec] ?: d.idleCapSec).coerceIn(30, 600),
            cacheLimitMb = (p[K.cacheLimitMb] ?: d.cacheLimitMb).coerceIn(64, 4096),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.dataStore.edit { it[key] = value } }

    suspend fun setTheme(v: ThemeMode) = set(K.theme, v.name)
    suspend fun setAccent(v: Accent) = set(K.accent, v.name)
    suspend fun setViewMode(v: ViewMode) = set(K.viewMode, v.name)
    suspend fun setGridColumns(v: Int) = set(K.gridColumns, v)
    suspend fun setSort(key: SortKey, descending: Boolean) {
        context.dataStore.edit { it[K.sortKey] = key.name; it[K.sortDescending] = descending }
    }
    suspend fun setIgnoredFolderNames(v: String) = set(K.ignoredFolderNames, v)
    suspend fun setAutoScanOnStart(v: Boolean) = set(K.autoScanOnStart, v)
    suspend fun setReaderMode(v: ReaderMode) = set(K.readerMode, v.name)
    suspend fun setDirection(v: ReadingDirection) = set(K.direction, v.name)
    suspend fun setFitMode(v: FitMode) = set(K.fitMode, v.name)
    suspend fun setPageAnimation(v: Boolean) = set(K.pageAnimation, v)
    suspend fun setBrightness(v: Float) = set(K.brightness, v)
    suspend fun setOrientation(v: OrientationLock) = set(K.orientation, v.name)
    suspend fun setKeepScreenOn(v: Boolean) = set(K.keepScreenOn, v)
    suspend fun setAutoSave(v: Boolean) = set(K.autoSave, v)
    suspend fun setSaveIntervalSec(v: Int) = set(K.saveIntervalSec, v)
    suspend fun setDefaultPpm(v: Float) = set(K.defaultPpm, v)
    suspend fun setShowEta(v: Boolean) = set(K.showEta, v)
    suspend fun setTrackTime(v: Boolean) = set(K.trackTime, v)
    suspend fun setHaptics(v: Boolean) = set(K.haptics, v)
    suspend fun setReduceMotion(v: Boolean) = set(K.reduceMotion, v)
    suspend fun setCompletionPercent(v: Int) = set(K.completionPercent, v)
    suspend fun setEndTolerance(v: Int) = set(K.endTolerance, v)
    suspend fun setIdleCapSec(v: Int) = set(K.idleCapSec, v)
    suspend fun setCacheLimitMb(v: Int) = set(K.cacheLimitMb, v)

    /** Configurações como texto, para o backup. */
    suspend fun exportAll(): Map<String, String> =
        context.dataStore.data.first().asMap().entries.associate { it.key.name to it.value.toString() }

    /** Restaura apenas chaves conhecidas, com o tipo correto; valores inválidos são ignorados. */
    suspend fun importAll(values: Map<String, String>) {
        context.dataStore.edit { prefs ->
            for (key in K.all) {
                val raw = values[key.name] ?: continue
                @Suppress("UNCHECKED_CAST")
                when {
                    key.name in BOOLEAN_KEYS -> raw.toBooleanStrictOrNull()?.let { prefs[key as Preferences.Key<Boolean>] = it }
                    key.name in INT_KEYS -> raw.toIntOrNull()?.let { prefs[key as Preferences.Key<Int>] = it }
                    key.name in FLOAT_KEYS -> raw.toFloatOrNull()?.let { prefs[key as Preferences.Key<Float>] = it }
                    else -> prefs[key as Preferences.Key<String>] = raw
                }
            }
        }
    }

    private companion object {
        val BOOLEAN_KEYS = setOf(
            "sortDescending", "autoScanOnStart", "pageAnimation", "keepScreenOn", "autoSave",
            "showEta", "trackTime", "haptics", "reduceMotion",
        )
        val INT_KEYS = setOf("gridColumns", "saveIntervalSec", "completionPercent", "endTolerance", "idleCapSec", "cacheLimitMb")
        val FLOAT_KEYS = setOf("brightness", "defaultPpm")
    }
}
