package com.hqverse.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hqverse.app.data.settings.AppSettings
import com.hqverse.app.ui.nav.AppNav
import com.hqverse.app.ui.theme.HQVerseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HQVerseApp).container
        setContent {
            val s by container.settings.settings.collectAsState(initial = AppSettings())
            // Respeita a configuração do sistema (Opções do desenvolvedor / Acessibilidade): escala de animação = 0.
            val systemNoAnim = remember0 { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            HQVerseTheme(s.theme, s.accent, s.reduceMotion || systemNoAnim) { AppNav(container) }
        }
    }
}

private inline fun <T> remember0(block: () -> T): T = block()
