package com.irvingguerra.litedash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.irvingguerra.litedash.core.ui.LiteDashTheme

class MainActivity : ComponentActivity() {
    private val dependencies get() = (application as LiteDashApp).dependencies

    // Sube cada vez que se vuelve a la app (p. ej. después de dar un permiso en Ajustes).
    private val revision = mutableIntStateOf(0)
    // Sube al tocar el botón de inicio estando ya en LiteDash: cierra lo que esté abierto.
    private val homePressed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Es la pantalla de inicio: "atrás" no la cierra (los paneles sí se cierran con atrás).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            LiteDashContent(dependencies, revision.intValue) { Home(dependencies, revision.intValue, homePressed.intValue) }
        }
    }

    // Idioma elegido en Ajustes (o el del sistema si no eligió).
    override fun attachBaseContext(base: Context) = super.attachBaseContext(contextWithLanguage(base))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.intValue++
    }

    // Mandos al volante: llegan como teclas normales de Android mientras LiteDash está al frente.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        dependencies.wheel.onKey(event, dependencies.music) || super.onKeyUp(keyCode, event)

    override fun onResume() {
        super.onResume()
        inFront = true
        dependencies.music.cancelReturn()
        // Al abrir LiteDash se busca al celular vinculado (sin celular, no hace nada).
        dependencies.phone.start()
        revision.intValue++
    }

    // Algunos teléfonos (Samsung) regresan la barra al cerrar un aviso o un diálogo, sin pasar por onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && dependencies.prefs.hideStatusBar) applyStatusBar(window, true)
    }

    override fun onPause() {
        super.onPause()
        inFront = false
    }

    companion object {
        /** LiteDash está al frente (para no traerlo de nuevo si ya lo está). */
        @Volatile var inFront = false
            private set

        /**
         * Abre el inicio otra vez desde cero (al cambiar de idioma). No con `recreate()`: Android
         * reusa la ventana vieja y en Android 15 quedaba sin borde a borde (una franja abajo y,
         * con otro alto, otro acomodo del inicio).
         */
        fun restart(activity: Activity) {
            restarted = true
            // Como inicio si así se abrió (si no, Android hacía otro al tocar Inicio) y en una tarea limpia:
            // con otro LiteDash abierto como app, Android le entregaba la orden a ese y no cambiaba el idioma.
            val intent = Intent(Intent.ACTION_MAIN).setClass(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (activity.intent?.hasCategory(Intent.CATEGORY_HOME) == true) intent.addCategory(Intent.CATEGORY_HOME)
            // Primero abrir y luego cerrar: Android solo deja la nueva como inicio si la abre un inicio vivo.
            activity.startActivity(intent)
            activity.finish()
            // Sin la animación de cierre de la vieja: el cambio se ve como un solo cuadro.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
            }
        }

        // La actividad nueva viene de [restart] (no de abrir LiteDash): sin animación de entrada.
        @Volatile private var restarted = false

        /** ¿Se reinició por [restart]? Lo dice una sola vez. */
        fun takeRestarted(): Boolean = restarted.also { restarted = false }

        /** Trae el inicio al frente sin tocar lo abierto (no es el botón de inicio: no cierra paneles). */
        fun bringToFront(context: Context) {
            if (inFront) return
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(intent) }
        }
    }
}

/** El tema (claro, oscuro o por la noche), el brillo y la escala de LiteDash alrededor de [content]. */
@Composable
internal fun ComponentActivity.LiteDashContent(d: Dependencies, revision: Int, content: @Composable () -> Unit) {
    // Una sola cuenta de la noche (sol o sensor de luz) para el tema y el brillo.
    val night = rememberIsNight(d.prefs, needed = d.prefs.theme == ThemeMode.SUN || d.prefs.brightnessBySun)
    val dark = when (d.prefs.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.SUN -> night
    }
    // Íconos de la barra del sistema claros u oscuros según el tema.
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(style, style)
    }
    // La barra de Android de arriba, oculta si se pidió: deslizando desde arriba se asoma un momento.
    // También al volver a LiteDash ([revision]): otra app o el apagado de pantalla la regresan.
    val hideStatusBar = d.prefs.hideStatusBar
    LaunchedEffect(hideStatusBar, revision) { applyStatusBar(window, hideStatusBar) }
    ApplyBrightness(d.prefs, night, revision)
    LiteDashTheme(dark = dark, scale = d.prefs.scale, accent = d.prefs.accentColor, content = content)
}

/** Oculta o muestra la barra de Android de arriba (deslizando desde arriba se asoma un momento). */
private fun applyStatusBar(window: android.view.Window, hide: Boolean) {
    val bars = WindowCompat.getInsetsController(window, window.decorView)
    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (hide) bars.hide(WindowInsetsCompat.Type.statusBars()) else bars.show(WindowInsetsCompat.Type.statusBars())
}
