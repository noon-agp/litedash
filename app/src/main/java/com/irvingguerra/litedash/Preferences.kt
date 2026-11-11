package com.irvingguerra.litedash

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.irvingguerra.litedash.feature.car.CarColors
import com.irvingguerra.litedash.feature.car.CarPaint
import com.irvingguerra.litedash.feature.car.DEFAULT_GLOSS
import com.irvingguerra.litedash.feature.car.PaintFinish
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Claves y valores guardados en el dispositivo, en inglés desde la 1.4.47. Lo que
 * guardaron las versiones anteriores (en español) lo copia una sola vez
 * [StorageMigration]; cambiar estos nombres borraría los ajustes de quien ya tiene LiteDash.
 */
internal object PrefKeys {
    const val FILE = "settings"
    const val THEME = "theme"
    const val NIGHT_SOURCE = "night_source"
    const val SCALE = "scale"
    const val CAR_NAME = "car_name"
    const val CAR_COLOR = "car_color_argb"
    const val CAR_FINISH = "car_finish"
    const val CAR_GLOSS = "car_gloss"
    /** Int ARGB; sin guardar = el color natural. */
    const val ACCENT_COLOR = "accent_color_argb"
    const val CAR_MODEL = "car_model"
    const val WELCOME_SEEN = "welcome_seen"
    const val WELCOME_STEP = "welcome_step"
    const val DONT_ASK_HOME = "dont_ask_home"
    const val OPEN_ON_BOOT = "open_on_boot"
    const val LANGUAGE = "language"
    const val HIDDEN = "hidden_apps"
    const val DOCK = "dock"
    const val MAP_APP = "map_app"
    const val MUSIC_APP = "music_app"
    const val DRIVER_NAME = "driver_name"
    const val HOME_ADDRESS = "home_address"
    const val WORK_ADDRESS = "work_address"
    const val PARKED_MODE = "parked_mode"
    const val PARKED_VIEW = "parked_view"
    const val WHEEL_KEYS = "wheel_keys"
    const val CAR_ANGLE = "car_angle"
    const val BRIGHTNESS_BY_SUN = "brightness_by_sun"
    const val DAY_BRIGHTNESS = "day_brightness"
    const val NIGHT_BRIGHTNESS = "night_brightness"
    const val BRIGHTNESS_ALL_APPS = "brightness_all_apps"
    const val BRIGHTNESS_ORIGINAL = "brightness_original"
    const val ENGINE_ALERTS = "engine_alerts"
    const val BAR_POSITION = "bar_position"
    const val BAR_SIZE = "bar_size"
    const val BAR_VOLUME = "bar_volume"
    const val BAR_CLOCK = "bar_clock"
    const val BAR_APPS = "bar_apps"
    const val BAR_FAVORITES = "bar_favorites"
    const val BAR_VOICE = "bar_voice"
    const val BAR_MUSIC = "bar_music"
    /** Los botones de la barra en orden ([BarItem]); sin guardar, se arman de las opciones de antes. */
    const val BAR_ITEMS = "bar_items"
    const val BAR_STYLE = "bar_style"
    const val LOGO_INTRO = "logo_intro"
    const val INTRO_MIGRATED = "intro_image_migrated"
    const val LIGHTS_KEY = "lights_key"
    const val LIGHTS_ON = "lights_on_value"
    const val SPLIT_SCREEN = "split_screen"
    const val SPLIT_CAR_LEFT = "split_car_left"
    const val SPLIT_ROAD = "split_road"
    const val TRIP_LOG = "trip_log"
    const val BEST_ACCEL = "best_0_100"
    const val STATS = "anonymous_stats"
    const val HIDE_STATUS_BAR = "hide_status_bar"
    const val INTERNET_RADIO = "internet_radio"
    const val RADIO_UNIFIED = "radio_unified"
    const val TIRES_ON_CAR = "tires_on_car"
    const val TIRES_BANNER = "tires_banner"
    const val CAR_TAP = "car_tap"
    /** Boolean; sin guardar = prendido si ya hay un adaptador OBD. */
    const val OBD_MODULE = "obd_module"
    const val SPEED_UNITS = "speed_units"
    const val TEMP_UNITS = "temp_units"
    /** Int: versión del almacenamiento ([StorageMigration]: 1 = nombres en inglés, 2 = tres piezas del auto). */
    const val STORAGE_VERSION = "storage_version"
}

/** Las llantas sobre la pieza del auto: pastillas en las ruedas, en las esquinas o nada. */
enum class TiresOnCar(val stored: String) {
    /** Pegadas a cada rueda del auto 3D al girarlo; sin los datos de sus ruedas, en las esquinas. */
    WHEELS("wheels"), CORNERS("in_corners"), OFF("off");

    companion object {
        // "corners" es de antes de la 1.4.82, cuando solo había "Mostrar" o "No": quería decir mostrarlas.
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: WHEELS
    }
}

/** Qué hace tocar el auto 3D con Llantas activo: verlo en grande o subir a ver sus llantas. */
enum class CarTap(val stored: String) {
    BIG("big"), TIRES("tires");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: TIRES
    }
}

/** Qué se ve en el modo estacionado: el reloj grande o tu imagen. */
enum class ParkedView(val stored: String) {
    CLOCK("clock"), IMAGE("image");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: CLOCK
    }
}

/** Dónde va la barra con la hora y tus apps. AUTO = a un lado si la pantalla está acostada. */
enum class BarPosition(val stored: String) {
    // SIDE = a la izquierda (como se guardaba antes de haber derecha).
    AUTO("auto"), SIDE("side"), RIGHT("right"), BOTTOM("bottom");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: AUTO
    }
}

/** Tamaño de la barra de apps (ancho al lado, alto abajo): botones e íconos a escala. */
enum class BarSize(val stored: String, val scale: Float) {
    SMALL("small", 0.82f), NORMAL("normal", 1f), LARGE("large", 1.2f);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: NORMAL
    }
}

/**
 * Un botón de la barra de apps. Los de antes de [FAVORITES] van a la izquierda (arriba en la lateral) y
 * los de después a la derecha; tus apps favoritas, al centro. [SETTINGS] siempre va y al final: sin él no
 * habría cómo salir del inicio.
 */
enum class BarItem(val stored: String) {
    APPS("apps"), MUSIC("music"), VOLUME("volume"), NAVIGATE("navigate"), PHONE("phone"),
    VOICE("voice"), FAVORITES("favorites"), SETTINGS("settings");

    companion object {
        fun fromStored(value: String) = entries.firstOrNull { it.stored == value }

        /** Ajustes al final y una sola vez; lo que no se conoce, fuera. */
        fun normalize(items: List<BarItem>) = items.distinct().filter { it != SETTINGS } + SETTINGS
    }
}

/**
 * La barra de fábrica: Apps, volumen y Música; tus favoritas (vacías: ninguna app de fábrica); Navegar, voz y
 * Ajustes. Teléfono se agrega si se quiere.
 */
private val DEFAULT_BAR = listOf(BarItem.APPS, BarItem.VOLUME, BarItem.MUSIC, BarItem.FAVORITES, BarItem.NAVIGATE, BarItem.VOICE)

/** Las opciones de la barra de antes de poder editarla: si alguna se guardó, se respeta lo de esa persona. */
private val LEGACY_BAR_KEYS = listOf(PrefKeys.BAR_APPS, PrefKeys.BAR_MUSIC, PrefKeys.BAR_VOLUME, PrefKeys.BAR_FAVORITES, PrefKeys.BAR_VOICE, PrefKeys.BAR_CLOCK)

/** Cómo se ve la barra de abajo: flotante (una píldora al centro, de fábrica) o a todo lo ancho. */
enum class BarStyle(val stored: String) {
    FLOATING("floating"), FULL("full");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: FLOATING
    }
}

/** [stored] es como se guarda cada tema. */
enum class ThemeMode(val stored: String) {
    DARK("dark"),
    LIGHT("light"),
    SYSTEM("system"),
    /** Claro de día y oscuro de noche (por el sol o el sensor de luz: [NightSource]). */
    SUN("sun");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/** Cómo se sabe si es de noche (para el tema "Día y noche" y el brillo). */
enum class NightSource(val stored: String) {
    /** Hora del sol: funciona en cualquier pantalla. */
    SUN("sun"),
    /** Sensor de luz de la pantalla (si tiene): túneles, estacionamientos, nublado. */
    SENSOR("sensor"),
    /** Los faros del auto, por el ajuste de la pantalla que LiteDash aprende ([LightsKey]). */
    LIGHTS("lights");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/** Idioma de la app: "" = el del sistema. Se lee antes de crear la pantalla. */
object Language {
    fun read(context: Context) = context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE).getString(PrefKeys.LANGUAGE, "") ?: ""
}

/** [base] con el idioma elegido en Ajustes. */
fun contextWithLanguage(base: Context): Context {
    val language = Language.read(base)
    if (language.isBlank()) return base
    // Con la región del sistema si habla ese idioma (es-MX: "12.6 V"); si no, México, que
    // escribe los decimales con punto (el español genérico usa coma).
    val system = primaryLocale(Resources.getSystem().configuration)
    val locale = if (system.language == language) system else Locale.forLanguageTag(if (language == "es") "es-MX" else language)
    val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
    return base.createConfigurationContext(config)
}

/**
 * Un ajuste guardado: se lee una vez al abrir, es estado de Compose (redibuja lo que lo
 * usa) y cada cambio se escribe al momento.
 */
private class Stored<T>(initial: T, private val save: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    private var value by mutableStateOf(initial)
    override fun getValue(thisRef: Any?, property: KProperty<*>) = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
        save(value)
    }
}

/** Ajustes del usuario: cada cambio se guarda al momento y redibuja lo que lo usa. */
class Preferences(context: Context) {
    private val p = context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)

    init {
        // Instalación nueva: tus favoritas de la barra empiezan vacías (antes se sugerían las instaladas, y quien
        // ya tenía LiteDash las conserva).
        if (!p.contains(PrefKeys.WELCOME_SEEN) && !p.contains(PrefKeys.DOCK)) p.edit { putString(PrefKeys.DOCK, "") }
    }

    private fun bool(key: String, default: Boolean) = Stored(p.getBoolean(key, default)) { p.edit { putBoolean(key, it) } }
    private fun int(key: String, default: Int) = Stored(p.getInt(key, default)) { p.edit { putInt(key, it) } }
    private fun float(key: String, default: Float) = Stored(p.getFloat(key, default)) { p.edit { putFloat(key, it) } }
    private fun string(key: String) = Stored(p.getString(key, null)) { p.edit { putString(key, it) } }
    private fun <T> mapped(key: String, read: (String?) -> T, write: (T) -> String?) =
        Stored(read(p.getString(key, null))) { p.edit { putString(key, write(it)) } }

    var theme by mapped(PrefKeys.THEME, { ThemeMode.fromStored(it) ?: ThemeMode.DARK }, { it.stored })

    var nightSource by mapped(PrefKeys.NIGHT_SOURCE, { NightSource.fromStored(it) ?: NightSource.SUN }, { it.stored })

    var scale by float(PrefKeys.SCALE, 1f)

    /** null = todavía no lo nombra (se muestra "Mi auto"). */
    var carName by string(PrefKeys.CAR_NAME)

    private var _carColor by mutableIntStateOf(p.getInt(PrefKeys.CAR_COLOR, CarColors[0].toArgb()))
    var carColor: Color
        get() = Color(_carColor)
        set(v) { _carColor = v.toArgb(); p.edit { putInt(PrefKeys.CAR_COLOR, _carColor) } }

    /** Mientras se arrastra el selector: el auto cambia en vivo y se guarda al soltar ([carColor]). */
    fun previewCarColor(v: Color) {
        _carColor = v.toArgb()
    }

    /** Acabado de la pintura del auto (de fábrica, Satinado: la pintura del paquete). */
    var paintFinish by mapped(PrefKeys.CAR_FINISH, PaintFinish::fromStored) { it.stored }

    private var _paintGloss by mutableFloatStateOf(p.getFloat(PrefKeys.CAR_GLOSS, DEFAULT_GLOSS))
    /** Cuánto brilla la pintura Brillante o Metálica (0–1). */
    var paintGloss: Float
        get() = _paintGloss
        set(v) { _paintGloss = v; p.edit { putFloat(PrefKeys.CAR_GLOSS, v) } }

    /** Mientras se arrastra el deslizador: el auto cambia en vivo y se guarda al soltar ([paintGloss]). */
    fun previewPaintGloss(v: Float) {
        _paintGloss = v
    }

    /** Cómo se pinta el auto 3D: color, acabado y brillo. */
    val carPaint get() = CarPaint(carColor, paintFinish, paintGloss)

    // El color de la interfaz; null = el natural (ámbar, con lo principal en blanco o negro).
    private var _accent by mutableStateOf(if (p.contains(PrefKeys.ACCENT_COLOR)) p.getInt(PrefKeys.ACCENT_COLOR, 0) else null)
    var accentColor: Color?
        get() = _accent?.let { Color(it) }
        set(v) {
            _accent = v?.toArgb()
            p.edit { if (v == null) remove(PrefKeys.ACCENT_COLOR) else putInt(PrefKeys.ACCENT_COLOR, v.toArgb()) }
        }

    /** Mientras se arrastra el selector: la interfaz cambia en vivo y se guarda al soltar ([accentColor]). */
    fun previewAccent(v: Color) {
        _accent = v.toArgb()
    }

    /** Id del auto del catálogo que se muestra; null = el sedán genérico. */
    var carModel by string(PrefKeys.CAR_MODEL)

    var welcomeSeen by bool(PrefKeys.WELCOME_SEEN, false)

    /** Se guarda porque Android reinicia la app al volverla pantalla de inicio. */
    var welcomeStep by int(PrefKeys.WELCOME_STEP, 0)

    /** El usuario pidió no volver a ver el aviso de "hazlo tu pantalla de inicio". */
    var dontAskHome by bool(PrefKeys.DONT_ASK_HOME, false)

    /** Abrir LiteDash al encender la pantalla ([BootLaunch]); apagado de fábrica. */
    var openOnBoot by bool(PrefKeys.OPEN_ON_BOOT, false)

    /** "" (sistema), "es" o "en". Cambiarlo recrea la actividad: se escribe al instante (commit). */
    var language by Stored(p.getString(PrefKeys.LANGUAGE, "") ?: "") { p.edit(commit = true) { putString(PrefKeys.LANGUAGE, it) } }

    /** Apps que no salen en el cajón ([com.irvingguerra.litedash.feature.apps.App.key]). */
    var hidden by Stored(p.getStringSet(PrefKeys.HIDDEN, emptySet())!!.toSet()) { p.edit { putStringSet(PrefKeys.HIDDEN, it) } }

    /** Apps fijas en el dock ([com.irvingguerra.litedash.feature.apps.App.key]); null = las sugeridas. */
    var dock by mapped(PrefKeys.DOCK, { v -> v?.split('\n')?.filter { it.isNotBlank() } }, { it?.joinToString("\n") })

    /** App de navegación elegida ([com.irvingguerra.litedash.feature.apps.App.key]); null = la primera instalada. */
    var mapApp by string(PrefKeys.MAP_APP)

    /** La app de música de antes de la 1.4.116: solo se lee una vez para pasarla a `MusicRepository.preferred`, que es la que vale. */
    var musicApp by string(PrefKeys.MUSIC_APP)

    /** Nombre de quien maneja, para saludarlo; null = sin saludo. */
    var driverName by string(PrefKeys.DRIVER_NAME)

    /** Dirección de Casa para el atajo del widget Mapa; null = sin guardar. */
    var homeAddress by string(PrefKeys.HOME_ADDRESS)

    /** Dirección de Trabajo para el atajo del widget Mapa; null = sin guardar. */
    var workAddress by string(PrefKeys.WORK_ADDRESS)

    /** Reloj grande tras unos minutos sin tocar el inicio. */
    var parkedMode by bool(PrefKeys.PARKED_MODE, true)

    /** Lo que muestra el modo estacionado (de fábrica, el reloj). */
    var parkedView by mapped(PrefKeys.PARKED_VIEW, ParkedView::fromStored) { it.stored }

    /** Dónde van la hora y tus apps: a un lado, abajo o según la pantalla. */
    var barPosition by mapped(PrefKeys.BAR_POSITION, BarPosition::fromStored) { it.stored }
    var barSize by mapped(PrefKeys.BAR_SIZE, BarSize::fromStored) { it.stored }
    var barStyle by mapped(PrefKeys.BAR_STYLE, BarStyle::fromStored) { it.stored }

    /** Los botones de la barra en orden (siempre con Ajustes al final, [BarItem.normalize]). */
    var barItems: List<BarItem>
        get() = storedBarItems
        set(value) {
            storedBarItems = BarItem.normalize(value)
        }
    private var storedBarItems by mapped(
        PrefKeys.BAR_ITEMS,
        { v -> BarItem.normalize(v?.split(',')?.mapNotNull(BarItem::fromStored) ?: legacyBarItems()) },
        { items -> BarItem.normalize(items).joinToString(",") { it.stored } },
    )

    /**
     * Los botones como se veían antes de poder editar la barra (Plan Widgets): de los interruptores y del
     * lugar del volumen de Ajustes → Barra de apps. Solo se leen mientras no se guarde una lista.
     */
    private fun legacyBarItems(): List<BarItem> {
        // Quien nunca tocó la barra: la de fábrica (Irving, 2026-11-09).
        if (LEGACY_BAR_KEYS.none(p::contains)) return DEFAULT_BAR
        val volume = p.getString(PrefKeys.BAR_VOLUME, null) ?: "end"
        return listOfNotNull(
            BarItem.APPS.takeIf { p.getBoolean(PrefKeys.BAR_APPS, true) },
            BarItem.MUSIC.takeIf { p.getBoolean(PrefKeys.BAR_MUSIC, true) },
            BarItem.VOLUME.takeIf { volume == "start" },
            BarItem.FAVORITES.takeIf { p.getBoolean(PrefKeys.BAR_FAVORITES, true) },
            BarItem.VOLUME.takeIf { volume == "end" },
            BarItem.VOICE.takeIf { p.getBoolean(PrefKeys.BAR_VOICE, false) },
        )
    }

    /** Abrir las apps de mapa y música en pantalla dividida, con el auto al lado (solo acostada). */
    var splitScreen by bool(PrefKeys.SPLIT_SCREEN, false)

    /** En pantalla dividida, el auto va a la izquierda (si no, a la derecha). */
    var splitCarLeft by bool(PrefKeys.SPLIT_CAR_LEFT, true)

    /** En pantalla dividida, al lado del mapa va la carretera (si no, el auto 3D). */
    var splitRoad by bool(PrefKeys.SPLIT_ROAD, false)

    /**
     * El interruptor de la radio por internet de antes de la 1.4.112: solo se lee una vez para pasar
     * a la radio escogida (`MusicRepository.radioInternet`), que es la que vale.
     */
    var internetRadio by bool(PrefKeys.INTERNET_RADIO, false)

    /** Ya se pasó [internetRadio] a la radio escogida. */
    var radioUnified by bool(PrefKeys.RADIO_UNIFIED, false)

    /** Guardar un resumen de cada viaje con el OBD conectado (apagado de fábrica). */
    var tripLog by bool(PrefKeys.TRIP_LOG, false)

    /** Ocultar la barra de Android de arriba (se asoma deslizando desde arriba); apagado de fábrica. */
    var hideStatusBar by bool(PrefKeys.HIDE_STATUS_BAR, false)

    /** Contar esta pantalla en las estadísticas anónimas (ver [Stats]); prendido de fábrica. */
    var stats by bool(PrefKeys.STATS, true)

    /** El mejor 0 a 100 (segundos); 0 = todavía no hay. */
    var bestAccel by float(PrefKeys.BEST_ACCEL, 0f)

    /** Llantas sobre la pieza del auto (con una fuente de llantas elegida). */
    var tiresOnCar by mapped(PrefKeys.TIRES_ON_CAR, TiresOnCar::fromStored) { it.stored }

    /** Al tocar el auto 3D con Llantas activo. */
    var carTap by mapped(PrefKeys.CAR_TAP, CarTap::fromStored) { it.stored }

    /** El módulo OBD prendido o apagado; null = nunca se tocó (ver [Dependencies.obdModule]). */
    var obdModule by Stored(if (p.contains(PrefKeys.OBD_MODULE)) p.getBoolean(PrefKeys.OBD_MODULE, false) else null) { v ->
        p.edit { if (v == null) remove(PrefKeys.OBD_MODULE) else putBoolean(PrefKeys.OBD_MODULE, v) }
    }

    /** Aviso arriba del inicio cuando una llanta tiene problema. */
    var tiresBanner by bool(PrefKeys.TIRES_BANNER, true)

    /** Velocidad y distancia: automático (según idioma y región), km o millas. */
    var speedUnits by mapped(PrefKeys.SPEED_UNITS, SpeedUnits::fromStored) { it.stored }

    /** Temperatura: automático (según idioma y región), °C o °F. */
    var tempUnits by mapped(PrefKeys.TEMP_UNITS, TempUnits::fromStored) { it.stored }

    /** Las unidades que se ven ahora (estado de Compose: cambia al elegir otras o cambiar el idioma). */
    val units: UnitChoice
        get() = resolveUnits(speedUnits, tempUnits, imperialUnits(language))

    /** Con el OBD conectado: avisar de motor caliente o batería que no carga. */
    var engineAlerts by bool(PrefKeys.ENGINE_ALERTS, true)

    /** Al abrir LiteDash, tu imagen (o el logo de LiteDash) aparece unos instantes a pantalla completa. */
    var logoIntro by bool(PrefKeys.LOGO_INTRO, false)

    /** Ajuste de la pantalla que dice si los faros están prendidos ("tabla/nombre"); null = sin aprender. */
    var lightsKey by string(PrefKeys.LIGHTS_KEY)

    /** El valor de ese ajuste con los faros prendidos. */
    var lightsOnValue by string(PrefKeys.LIGHTS_ON)

    /** Hacia dónde dejó girado el auto en el inicio (fracción de vuelta); null = tres cuartos. */
    var carAngle by Stored(p.getFloat(PrefKeys.CAR_ANGLE, -1f).takeIf { it >= 0f }) { p.edit { putFloat(PrefKeys.CAR_ANGLE, it ?: -1f) } }

    /** Botón del volante (código de tecla de Android) → acción guardada ([WheelAction.stored]). */
    var wheelKeys by mapped(
        PrefKeys.WHEEL_KEYS,
        { v ->
            (v ?: "").lines().mapNotNull { line ->
                val (code, action) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                code.toIntOrNull()?.let { it to action }
            }.toMap()
        },
        { m -> m.entries.joinToString("\n") { "${it.key}=${it.value}" } },
    )

    /** Brillo de día y de noche según el sol (apagado = el brillo del sistema, sin tocarlo). */
    var brightnessBySun by bool(PrefKeys.BRIGHTNESS_BY_SUN, false)

    /** Por ciento, de 10 a 100. */
    var dayBrightness by int(PrefKeys.DAY_BRIGHTNESS, 100)

    /** Por ciento, de 10 a 100. */
    var nightBrightness by int(PrefKeys.NIGHT_BRIGHTNESS, 40)

    /** También en las demás apps (cambia el brillo del sistema; pide "modificar ajustes del sistema"). */
    var brightnessAllApps by bool(PrefKeys.BRIGHTNESS_ALL_APPS, false)

    /**
     * El brillo del sistema como estaba antes de que LiteDash lo cambiara ("modo,nivel"):
     * se regresa al apagar el brillo en todas las apps. null = LiteDash no lo ha tocado.
     */
    var brightnessOriginal: Pair<Int, Int>?
        get() = p.getString(PrefKeys.BRIGHTNESS_ORIGINAL, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        set(v) = p.edit { putString(PrefKeys.BRIGHTNESS_ORIGINAL, v?.let { "${it.first},${it.second}" }) }
}
