package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.cardless
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.music.MusicMode
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.music.OnFilesEmpty
import com.irvingguerra.litedash.feature.music.RadioTile
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.updater.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Drawer : Panel
    data object Settings : Panel
    /** Ajustes abiertos en Sistema → Actualizaciones (desde el aviso de versión nueva). */
    data object Updates : Panel
    data object AddTile : Panel
    data object Templates : Panel
    /** El fondo de pantalla del inicio (botón "Fondo" al acomodar). */
    data object Wallpaper : Panel
    /** Lo de la barra de apps (botón "Barra" al acomodarla): agregar botones y apps, posición, tamaño, estilo. */
    data object BarSettings : Panel
    /** Elegir app para una pieza existente ([tileId]) o para una nueva (null); al elegir, regresa a [back]. */
    data class PickApp(val tileId: String?, val back: Panel? = null) : Panel
    /** Ajustes abiertos en el Taller (pieza de acceso directo). */
    data object Workshop : Panel
    /** Ajustes abiertos en Apps (para enlazar la radio de la pantalla desde la radio en grande). */
    data object MusicSettings : Panel
    /** Elegir qué dato muestra un medidor existente ([tileId]) o uno nuevo (null). */
    data class PickGauge(val tileId: String?) : Panel
    /** Estilo, color y demás de un velocímetro o medidor ([tileId]). */
    data class CustomizeGauge(val tileId: String) : Panel
    /** La foto de una pieza de imagen ([tileId]). */
    data class CustomizeImage(val tileId: String) : Panel
    /** Orientación, vista, zoom y colores del widget Mapa ([tileId]). */
    data class CustomizeMap(val tileId: String) : Panel
    /** La presión de las llantas sobre el auto 3D ([tileId]). */
    data class CustomizeCar(val tileId: String) : Panel
    /** Las opciones de un widget sin panel propio ([WIDGET_OPTIONS]): reloj, clima, música… */
    data class CustomizeWidget(val tileId: String) : Panel
    /** Ajustes abiertos en Llantas (desde la vista de llantas o la pieza sin configurar). */
    data object TiresSettings : Panel
}

class Dependencies(
    val apps: AppRepository,
    val music: MusicRepository,
    val navigation: NavigationRepository,
    val prefs: Preferences,
    val dashboard: DashboardState,
    val cars: CarRepository,
    val updater: Updater,
    val weather: WeatherRepository,
    val trip: TripRepository,
    val reminders: ReminderRepository,
    val wheel: WheelKeys,
    val stats: Stats,
    /** LiteDash Pro (en prueba): [ProAccess.isPro] lo leen las funciones Pro. */
    val pro: ProAccess,
    /** Señales del auto (OBD, GPS…): el tablero solo lee de aquí. */
    val bus: VehicleBus,
    private val obdLazy: Lazy<ObdRepository>,
    private val obdSaved: () -> Boolean,
    private val tpmsLazy: Lazy<TpmsRepository>,
    private val tpmsSaved: () -> Boolean,
    private val phoneLazy: Lazy<PhoneLink>,
) {
    /** Estado de Compose: las piezas que preguntaron antes de que existiera se vuelven a dibujar. */
    private val obdCreated = mutableStateOf(false)

    /** El escáner OBD: se crea la primera vez que algo lo pide (el Taller o una pieza). */
    val obd: ObdRepository
        get() = obdLazy.value.also { if (!obdCreated.value) obdCreated.value = true }

    /** Ya se usó un adaptador OBD alguna vez (sin crear el escáner para saberlo). */
    val hasObdAdapter get() = obdSaved()

    /**
     * El módulo OBD prendido (de fábrica, si ya hay adaptador). Apagado nada se conecta solo;
     * apagarlo desconecta.
     */
    var obdModule: Boolean
        get() = prefs.obdModule ?: hasObdAdapter
        set(on) {
            prefs.obdModule = on
            if (!on) obdIfCreated()?.disconnect()
        }

    /** Hay adaptador y el módulo está prendido: las piezas pueden conectarse solas. */
    val obdAuto get() = hasObdAdapter && obdModule

    /** El escáner si ya existe, sin crearlo (una pieza solo quiere saber si está conectado). */
    fun obdIfCreated(): ObdRepository? = if (obdCreated.value) obdLazy.value else null

    /**
     * El enlace con LiteDash Connect (Ajustes → Cuenta). Siempre disponible, pero sin celular vinculado
     * no escucha la red ni busca nada: solo trabaja con la sección abierta (el QR) o ya vinculado.
     */
    val phone: PhoneLink get() = phoneLazy.value

    private val tpmsCreated = mutableStateOf(false)

    init {
        // Las piezas de un módulo apagado no se ven ni apartan su lugar (ni las ponen las plantillas).
        dashboard.hidden = { type -> moduleOff(this, type) != null }
        dashboard.limits = ::limitsOf
    }

    /** Las llantas: se crean la primera vez que algo las pide (sus Ajustes o una pieza con fuente elegida). */
    val tpms: TpmsRepository
        get() = tpmsLazy.value.also { if (!tpmsCreated.value) tpmsCreated.value = true }

    /**
     * Hay una fuente de llantas elegida. Sin crear el repositorio para saberlo; ya creado, se
     * lee su estado (al elegir o quitar la fuente, el inicio se entera solo).
     */
    val hasTpmsSource: Boolean
        get() = if (tpmsCreated.value) tpmsLazy.value.source != null else tpmsSaved()
}

/**
 * Qué hace cada pieza al tocarla (abrir un panel, el mapa o el auto en grande). Se crea
 * una vez: sus funciones leen el estado del inicio al llamarse.
 */
class TileActions(
    val onPanel: (Panel) -> Unit,
    val onNavigate: (App?) -> Unit,
    /** Ver en grande el auto, la carretera o la imagen de esta pieza. */
    val onCarOnly: (Tile) -> Unit,
    /** Dónde está cada pieza que crece (por id): de ahí crece al verla sola. */
    val onCarBounds: (String, Rect) -> Unit,
    /** Si la pieza (por id) se esconde porque se ve en grande; se lee al dibujar (no recompone el tablero en cada cuadro de la animación). */
    val carHidden: (String) -> Boolean,
    /** Las llantas a pantalla completa. */
    val onTires: () -> Unit,
    /** Acomodando el inicio (las pastillas de llantas se esconden: estorban a los botones). */
    val editing: () -> Boolean,
    /** Abre la pantalla Música en esa pestaña (play sin nada que sonar: ahí está la biblioteca). */
    val onMedia: (MediaTab) -> Unit,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun TileContent(d: Dependencies, p: Tile, apps: List<App>, car: CarPackage?, revision: Int, actions: TileActions) {
    val context = LocalContext.current
    when (p.type) {
        TileTypes.CAR, TileTypes.ROAD, TileTypes.IMAGE -> {
            val hero = Modifier
                .fillMaxSize()
                .onGloballyPositioned { actions.onCarBounds(p.id, it.boundsInRoot()) }
                // Mientras crece o se ve sola, la de la pieza se esconde (si no, se ve doble).
                .graphicsLayer { alpha = if (actions.carHidden(p.id)) 0f else 1f }
            val content = @Composable { onTires: (() -> Unit)? ->
                HeroTile(
                    d,
                    p,
                    car,
                    // Una imagen sin foto abre su panel para elegirla; lo demás crece.
                    onClick = { if (p.type == TileTypes.IMAGE && ImageData.name(p.data).isEmpty()) actions.onPanel(Panel.CustomizeImage(p.id)) else actions.onCarOnly(p) },
                    modifier = Modifier.fillMaxSize(),
                    onTires = onTires,
                )
            }
            // Las llantas junto a cada rueda en la carretera y en el auto 3D (si ya se sabe dónde pisan
            // y así se eligió); si no, en las esquinas. En una pieza chica las cuatro pastillas
            // taparían todo: ahí solo la pieza Llantas o el aviso. Sin llantas no hace falta medir la pieza.
            // Cada carretera y auto dice si las lleva (su ajuste); si no lo tiene, lo de Ajustes → Llantas.
            if (d.hasTpmsSource && showsTires(d, p)) BoxWithConstraints(hero) {
                val wheels = d.prefs.tiresOnCar != TiresOnCar.CORNERS
                // Dónde pisa cada rueda: un archivo chico aparte del paquete, se pide la primera vez.
                if (p.type == TileTypes.CAR && wheels) LaunchedEffect(car) { d.cars.fetchWheels(car) }
                val pills = maxWidth >= PILLS_MIN_WIDTH && maxHeight >= PILLS_MIN_HEIGHT && !actions.editing()
                val onWheels = p.type == TileTypes.ROAD || p.type == TileTypes.CAR && wheels && car?.wheels != null
                if (onWheels) content(actions.onTires.takeIf { pills }) else {
                    content(null)
                    if (pills) TirePills(d, actions.onTires)
                }
            } else Box(hero) { content(null) }
        }
        // Aunque sean grandes, sin biblioteca ni estaciones: eso vive en la pantalla Música.
        TileTypes.MUSIC -> MusicTile(d.music, revision, artwork = p.flag(WidgetFlags.ART), skip = p.flag(WidgetFlags.SKIP))
        TileTypes.RADIO -> RadioTile(
            d.music, revision,
            // Play sin estación: la radio por internet elige en la pantalla Música; sin radio enlazada, los ajustes.
            onIdle = {
                if (d.music.radioInternet) actions.onMedia(MediaTab.RADIO)
                else {
                    openRadioSettings()
                    actions.onPanel(Panel.MusicSettings)
                }
            },
            artwork = p.flag(WidgetFlags.ART), skip = p.flag(WidgetFlags.SKIP),
        )
        TileTypes.CLOCK -> ClockTile(d.prefs.driverName.takeIf { p.flag(WidgetFlags.GREETING) }, clockWeather(d, p.data), date = p.flag(WidgetFlags.DATE))
        TileTypes.WEATHER -> WeatherTile(d.weather, days = p.flag(WidgetFlags.DAYS), range = p.flag(WidgetFlags.RANGE))
        TileTypes.TRIP -> TripTile(d, time = p.flag(WidgetFlags.TIME), average = p.flag(WidgetFlags.AVERAGE), heading = p.flag(WidgetFlags.HEADING))
        TileTypes.SPEEDOMETER -> SpeedometerTile(d, remember(p.data) { GaugeLook.parse(p.data) })
        TileTypes.GAUGE -> GaugeTile(
            d, signalOf(p.data), remember(p.data) { GaugeLook.parse(p.data) },
            onPick = { actions.onPanel(Panel.PickGauge(p.id)) },
            onWorkshop = { actions.onPanel(Panel.Workshop) },
        )
        TileTypes.WORKSHOP -> HealthTile(d, car, showCar = p.flag(WidgetFlags.CAR)) { actions.onPanel(Panel.Workshop) }
        TileTypes.PROJECTION -> ProjectionTile(d, apps, appKeyOf(p.data)) { actions.onPanel(Panel.Drawer) }
        TileTypes.TIRES -> TiresTile(d, showCar = p.flag(WidgetFlags.CAR), onOpen = actions.onTires, onSettings = { actions.onPanel(Panel.TiresSettings) })
        TileTypes.MAP -> {
            val app = mapApp(d, apps)
            MapTile(
                d, p.data, app, editing = actions.editing(),
                onPlace = remember(app) { { navigateTo(context, app, it) } },
                onNavigate = remember(app) { { actions.onNavigate(app) } },
            )
        }
        TileTypes.PHONE -> {
            // La app de llamadas que se eligió (la del Bluetooth de la pantalla, por ejemplo) o el marcador.
            val app = appKeyOf(p.data)?.let { d.apps.find(apps, it) }
            ShortcutTile(
                Icons.phone, stringResource(R.string.tile_phone), app?.name,
                onClick = { if (app != null) d.apps.open(context, app) else openDialer(context) },
                tint = Good,
            )
        }
        TileTypes.APP -> {
            val app = appKeyOf(p.data)?.let { d.apps.find(apps, it) }
            AppTile(d.apps, app, named = p.flag(WidgetFlags.NAME)) {
                if (app != null) d.apps.open(context, app) else actions.onPanel(Panel.PickApp(p.id))
            }
        }
    }
}

/**
 * El módulo apagado del que depende una pieza (su nombre), o null si se puede ver: el Taller y
 * los medidores son del OBD, la pieza Llantas de Llantas. Esas piezas no se ven ni apartan su
 * lugar ([DashboardState.hidden]) y no se ofrecen para agregar.
 */
fun moduleOff(d: Dependencies, type: String): Int? = when (type) {
    TileTypes.WORKSHOP, TileTypes.GAUGE -> R.string.module_obd.takeIf { !d.obdModule }
    TileTypes.TIRES -> R.string.module_tpms.takeIf { !d.hasTpmsSource }
    else -> null
}

/**
 * Si la carretera, el auto 3D o la imagen [p] llevan la presión de las llantas: lo de su ajuste ([TileTires]);
 * sin él, el auto y la carretera siguen lo de Ajustes → Llantas y la imagen no las lleva (no tiene ruedas).
 */
fun showsTires(d: Dependencies, p: Tile): Boolean =
    TileTires.parse(p.data) ?: (p.type != TileTypes.IMAGE && d.prefs.tiresOnCar != TiresOnCar.OFF)

/** Lo que se ofrece para agregar: Llantas siempre (agregarla prende su módulo); lo del OBD, con el OBD. */
fun offered(d: Dependencies, type: String) = type == TileTypes.TIRES || moduleOff(d, type) == null

/**
 * Agrega la pieza Llantas y prende su módulo: vuelve la última fuente; si nunca hubo, [pickSource]
 * (Ajustes → Llantas) y la pieza aparece al elegirla. false = no cupo.
 */
fun Dependencies.addTiresTile(pickSource: () -> Unit): Boolean {
    if (!hasTpmsSource) tpms.lastSource?.let { tpms.source = it } ?: pickSource()
    return dashboard.tiles.any { it.type == TileTypes.TIRES } || dashboard.addTile(TileTypes.TIRES)
}

/** La de Ajustes; si no, la primera instalada. */
fun mapApp(d: Dependencies, apps: List<App>): App? =
    d.prefs.mapApp?.let { d.apps.find(apps, it) }
        ?: d.apps.firstOf(apps, *NAVIGATION_PACKAGES)

/** Inicio: barra de estado, tablero personalizable y dock. */
@Composable
fun Home(d: Dependencies, revision: Int, homePressed: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val currentApps by rememberUpdatedState(apps)
    // Voz: el asistente que tenga la pantalla (volante y barra); null si no hay ninguno (sin botón).
    val onVoice: (() -> Unit)? = remember {
        voiceIntent(context)?.let { intent -> { runCatching { context.startActivity(intent) }; Unit } }
    }
    val car = d.cars.pkg(d.prefs.carModel)
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editing by remember { mutableStateOf(false) }
    // LiteDash puede estar abierto dos veces (como inicio y como app) y el acomodo es uno solo: la
    // cuadrícula la decide la ventana que se está usando. Si no, la de atrás (que no se vuelve a medir
    // y se queda con la escala de antes) recargaba su acomodo en cada cambio y deshacía lo que se movía.
    var inFront by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        inFront = true
        onPauseOrDispose { inFront = false }
    }
    // La pantalla Música a media pantalla (su botón de la barra o el del volante); false = el tablero normal.
    var musicBig by remember { mutableStateOf(false) }
    var mediaTab by remember { mutableStateOf(MediaTab.MUSIC) }
    var mediaBrowse by remember { mutableStateOf(false) }
    // De qué lado va la pantalla Música (se decide al abrirla: no brinca si su widget cambia).
    var mediaSideFirst by remember { mutableStateOf(false) }
    // Solo el auto a pantalla completa (un toque al auto entra y otro sale), con animación:
    // [carOnly] es a dónde va y [carShown] si la capa sigue en pantalla (también al regresar).
    var carOnly by remember { mutableStateOf(false) }
    // La pieza que se ve en grande (auto, carretera o imagen) y dónde está cada una.
    var bigTile by remember { mutableStateOf<Tile?>(null) }
    val tileBounds = remember { mutableStateMapOf<String, Rect>() }
    // Las llantas a pantalla completa: [tiresOpen] es a dónde va, [tiresShown] si sigue en pantalla.
    var tiresOpen by remember { mutableStateOf(false) }
    var tiresShown by remember { mutableStateOf(false) }
    var tiresReady by remember { mutableStateOf(false) }
    val tiresProgress = remember { Animatable(0f) }
    LaunchedEffect(tiresOpen) {
        if (tiresOpen) {
            tiresShown = true
            // Aparece cuando el auto de arriba ya está pintado (la primera vez tarda en leerse); si no, igual.
            withTimeoutOrNull(TIRES_WAIT_MS) { snapshotFlow { tiresReady }.first { it } }
            tiresProgress.animateTo(1f, tween(TIRES_FADE_MS, easing = FastOutSlowInEasing))
        } else if (tiresShown) {
            tiresProgress.animateTo(0f, tween(TIRES_FADE_MS, easing = FastOutSlowInEasing))
            tiresShown = false
            tiresReady = false
        }
    }
    fun openTires() {
        tiresOpen = true
    }
    // La pantalla Música en [tab] (null = la de lo que suena), del lado de su widget si lo hay; [browse] la abre
    // en la biblioteca.
    fun openMedia(tab: MediaTab?, browse: Boolean = false) {
        val shown = tab ?: if (d.music.radio.song?.playing == true) MediaTab.RADIO else MediaTab.MUSIC
        panel = null
        tiresOpen = false
        carOnly = false
        editing = false
        mediaTab = shown
        mediaBrowse = browse
        // Crece en la mitad donde está su widget: derecha (o abajo) si está toda ahí; si no, izquierda (o arriba).
        val from = d.dashboard.shown.firstOrNull { it.type == if (shown == MediaTab.RADIO) TileTypes.RADIO else TileTypes.MUSIC }
        val grid = d.dashboard.grid
        mediaSideFirst = from != null && grid != null && if (grid.vertical) from.row * 2 >= grid.rows else from.col * 2 >= grid.columns
        musicBig = true
    }
    // Con Archivos, play sin nada que sonar (volante, barra de Android, widget) abre la pantalla Música en la
    // biblioteca; mientras está abierta, la atiende ella.
    val currentOpenMedia by rememberUpdatedState(::openMedia)
    OnFilesEmpty(d.music, d.music.mode == MusicMode.FILES, remember { { currentOpenMedia(MediaTab.MUSIC, true) } })
    fun backToDashboard() {
        panel = null
        tiresOpen = false
        carOnly = false
        musicBig = false
        editing = false
    }

    var carShown by remember { mutableStateOf(false) }
    var carReady by remember { mutableStateOf(false) }
    val carProgress = remember { Animatable(0f) }
    LaunchedEffect(carOnly) {
        if (carOnly) {
            carShown = true
            // Crece cuando la imagen grande ya está pintada (en su pieza se ve igual que la
            // chica); si tarda, crece de todos modos.
            withTimeoutOrNull(600) { snapshotFlow { carReady }.first { it } }
            carProgress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        } else if (carShown) {
            carProgress.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
            carShown = false
            carReady = false
        }
    }

    // Cada toque y cada botón del volante reinician la cuenta del modo estacionado. Se
    // leen dentro del efecto (no al armar la pantalla): tocar no vuelve a armar el inicio.
    var touched by remember { mutableIntStateOf(0) }
    var parked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(touched, d.wheel.presses, panel, editing, musicBig, carOnly, tiresOpen, d.prefs.parkedMode) }
            .collectLatest {
                parked = false
                if (!d.prefs.parkedMode || panel != null || editing || musicBig || carOnly || tiresOpen) return@collectLatest
                delay(PARK_AFTER_MS)
                // Manejando no: espera a que se detenga (velocidad del OBD o del GPS, la que haya).
                snapshotFlow { (d.bus.value(Signal.SPEED) ?: 0f) > MOVING_KMH }.first { !it }
                parked = true
            }
    }
    // Lo que sigue aunque se abra Ajustes o se acomode el inicio: el registro del viaje y el
    // aviso del motor (si no, abrir Ajustes cortaría el viaje y "Entendido" se olvidaría).
    val engineAlert = remember { EngineAlertState() }
    if (d.prefs.welcomeSeen) TripRecorderEffect(d)

    BackHandler(enabled = (panel != null || editing || musicBig || carOnly || tiresOpen) && d.prefs.welcomeSeen) {
        when {
            panel != null -> panel = null
            tiresOpen -> tiresOpen = false
            carOnly -> carOnly = false
            musicBig -> musicBig = false
            else -> editing = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    LaunchedEffect(homePressed) { if (d.prefs.welcomeSeen) backToDashboard() }
    // Clima al volver al inicio y cada media hora con el inicio a la vista (si está activado); nunca en segundo plano.
    WeatherRefresh(d.weather, revision)
    // Versión nueva de la app (a lo más cada 12 h, al volver al inicio) y modelo 3D nuevo del auto (cada hora).
    LaunchedEffect(revision) { d.updater.checkQuietly() }
    LaunchedEffect(d.prefs.carModel) { d.cars.checkQuietly(d.prefs.carModel) }
    // Cuántas pantallas usan LiteDash: a lo más una vez al día.
    LaunchedEffect(revision) { d.stats.pingDaily() }
    // Pro: lee lo guardado y revisa el código a lo más cada 12 h (sin código guardado, nada de red).
    LaunchedEffect(revision) { d.pro.refresh() }

    val actions = remember {
        TileActions(
            onPanel = { panel = it },
            onNavigate = { app -> openNavigation(context, d.apps, app) },
            onCarOnly = {
                // Con Llantas activo, tocar el auto 3D abre las llantas; si no, lo agranda.
                if (it.type == TileTypes.CAR && d.hasTpmsSource && d.prefs.carTap == CarTap.TIRES) openTires()
                else {
                    bigTile = it
                    carOnly = true
                }
            },
            onCarBounds = { id, rect -> if (tileBounds[id] != rect) tileBounds[id] = rect },
            carHidden = { id -> carShown && bigTile?.id == id && carProgress.value > 0f },
            onTires = { openTires() },
            editing = { editing },
            onMedia = { openMedia(it) },
        )
    }

    // Mandos al volante: las acciones que abren algo (mapa, música, voz, inicio) se hacen
    // aquí, una sola vez cada una.
    LaunchedEffect(Unit) {
        snapshotFlow { d.wheel.request }.filterNotNull().collect { action ->
            d.wheel.consume()
            when (action) {
                WheelAction.NAVIGATE -> actions.onNavigate(mapApp(d, currentApps))
                WheelAction.MUSIC -> openMedia(null)
                WheelAction.VOICE -> onVoice?.invoke()
                WheelAction.HOME -> backToDashboard()
                else -> Unit
            }
        }
    }

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val hasFloor = car?.hasFloor == true && d.dashboard.tiles.any { it.type == TileTypes.CAR }
    // Las fotos de piezas de imagen que se quitaron (o cambiaron) salen de memoria.
    LaunchedEffect(d.dashboard.tiles) { releaseTileImages(d.dashboard.tiles) }
    // Velocímetros guardados hasta la 1.4.61 (su máximo en la unidad que se veía): a km/h.
    LaunchedEffect(d.dashboard.tiles) {
        val miles = d.prefs.units.miles
        d.dashboard.changeData(
            d.dashboard.tiles.filter { it.type == TileTypes.SPEEDOMETER }
                .mapNotNull { t -> migrateSpeedMax(t.data, miles)?.let { t.id to it } }
                .toMap(),
        )
    }
    val backdrop = if (hasFloor) {
        Brush.verticalGradient(0.3f to c.background, 1f to lerp(c.background, Color.Black, if (c.dark) 0.35f else 0.08f))
    } else SolidColor(c.background)
    var promptsHeight by remember { mutableIntStateOf(0) }
    // Lo que mide una celda del inicio (la última vez que se dibujó): la vista previa de las opciones de un
    // widget lo muestra a su tamaño real.
    var cell by remember { mutableStateOf(DpSize(48.dp, 48.dp)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            // Cualquier toque reinicia la cuenta del modo estacionado (sin quitarle el toque a nadie).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched++
                }
            },
    ) {
        // Tu imagen de fondo (Inicio y apariencia → Inicio → Fondo de pantalla), detrás de todo.
        if (panel == null) HomeWallpaper()
        // Los paneles tapan todo el inicio: mientras hay uno abierto no se dibuja ni se
        // pinta nada debajo (el auto del tablero no se tiñe dos veces al cambiar el color).
        if (panel == null) BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
          // Pantalla acostada: barra lateral a la izquierda (como DuduAuto), con la hora y tus
          // apps; el tablero se queda con toda la altura. Parada: el dock abajo, como siempre.
          val rail = when (d.prefs.barPosition) {
              BarPosition.SIDE, BarPosition.RIGHT -> true
              BarPosition.BOTTOM -> false
              BarPosition.AUTO -> maxWidth > maxHeight && !isShortScreen()
          }
          val favorites = remember(apps, d.prefs.dock, d.music.preferred) { dockFavorites(d, apps) }
          val railRight = rail && d.prefs.barPosition == BarPosition.RIGHT
          val barActions = BarActions(
              onApps = { panel = Panel.Drawer },
              onMusic = { if (musicBig) musicBig = false else openMedia(null) },
              onNavigate = { actions.onNavigate(mapApp(d, apps)) },
              onPhone = { openDialer(context) },
              onVoice = onVoice,
              onSettings = { panel = Panel.Settings },
              onBarSettings = { panel = Panel.BarSettings },
              onEdit = { musicBig = false; editing = true },
          )
          val bar = @Composable { vertical: Boolean ->
              AppBar(d.apps, d.prefs, favorites, vertical, editing, musicBig, barActions)
          }
          val railBar = @Composable { bar(true) }
          Row(Modifier.fillMaxSize()) {
            if (rail && !railRight) railBar()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Si ya hay un reloj en el tablero, la barra de arriba no repite la hora.
                val showTime = d.dashboard.tiles.none { it.type == TileTypes.CLOCK }
                val barHidden = !editing && !showTime
                TopBar(
                    editing = editing,
                    showTime = showTime,
                    driverName = d.prefs.driverName,
                    weather = d.weather,
                    onAdd = { panel = Panel.AddTile },
                    onTemplates = { panel = Panel.Templates },
                    onWallpaper = { panel = Panel.Wallpaper },
                    onFinish = { editing = false },
                )
                // Los avisos tampoco cuentan para la cuadrícula: aparecen y se van, y no deben
                // hacer que se cargue otro acomodo.
                Column(Modifier.onSizeChanged { promptsHeight = it.height }) {
                    if (d.prefs.welcomeSeen && !editing) {
                        if (!d.prefs.dontAskHome && !isShortScreen()) HomePrompt(d.prefs, revision)
                        EngineAlertBanner(d, engineAlert)
                        TiresAlertBanner(d, actions.onTires)
                        if (!isShortScreen()) ReminderBanner(d.reminders)
                        UpdateBanner(d.updater, onOpen = { openUpdates(); panel = Panel.Updates })
                        val newModel = d.cars.outdated?.takeIf { it.id == car?.id }
                        if (car != null && newModel != null) CarUpdateNotice(d.cars, car, newModel)
                    }
                }
                // Con la barra al costado no hay dock abajo: el tablero lleva su propio aire inferior
                // (en pantallas sin barra de gestos las piezas quedaban pegadas al borde).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (rail) 10.dp else 0.dp)) {
                    // La cuadrícula se calcula siempre como si la barra estuviera: si dependiera
                    // de ella, un reloj en el acomodo la oculta, la cuadrícula crece, se carga
                    // otro acomodo sin reloj, vuelve la barra... y el inicio parpadea sin parar.
                    val prompts = with(LocalDensity.current) { promptsHeight.toDp() }
                    // El aire inferior de la barra lateral tampoco cuenta: la cuadrícula (y el acomodo
                    // guardado) debe ser la misma con o sin él.
                    // Con la barra de Android oculta hay más alto, pero la cuadrícula tampoco cambia.
                    val statusHidden = statusBarHiddenHeight()
                    val gridHeight = (if (barHidden) maxHeight - (topBarHeight() - TOP_BAR_HIDDEN) else maxHeight) + prompts + (if (rail) 10.dp else 0.dp) - statusHidden
                    val grid = remember(maxWidth, gridHeight) { Grid.forSize(maxWidth.value, gridHeight.value) }
                    // Carga el acomodo de esta cuadrícula antes de dibujar (solo hace algo si cambió).
                    if (inFront || d.dashboard.grid == null) d.dashboard.use(grid)
                    val cellNow = DpSize(maxWidth / grid.columns, maxHeight / grid.rows)
                    SideEffect { cell = cellNow }
                    // La pantalla Música ocupa media pantalla; en la otra mitad, el auto como en la
                    // pantalla dividida (auto 3D o carretera, según se eligió ahí), o tu imagen.
                    // Con algo a pantalla completa encima, lo que gasta en los widgets (GPS, mapa) descansa.
                    CompositionLocalProvider(LocalHomeCovered provides (carShown || tiresShown || parked)) {
                        if (musicBig) HalfScreen(
                            side = {
                                val shown = d.dashboard.shown
                                val hero = shown.firstOrNull { it.type == (if (d.prefs.splitRoad) TileTypes.ROAD else TileTypes.CAR) } ?: shown.firstOrNull { it.cardless }
                                if (hero != null) TileContent(d, hero, apps, car, revision, actions) else ClockTile(d.prefs.driverName, d.weather)
                            },
                            sideFirst = mediaSideFirst,
                            vertical = grid.vertical,
                        ) {
                            MediaScreen(
                                d, mediaTab, onTab = { mediaTab = it; mediaBrowse = false }, close = { musicBig = false },
                                onRadioSettings = { openRadioSettings(); panel = Panel.MusicSettings },
                                modifier = Modifier.padding(6.dp),
                                browse = mediaBrowse,
                            )
                        } else Dashboard(
                                grid = grid,
                                tiles = d.dashboard.shown,
                                editing = editing,
                                limits = ::limitsOf,
                                onChange = d.dashboard::changeShown,
                                onEdit = { editing = true },
                                onSettings = {
                                    panel = when (it.type) {
                                        TileTypes.IMAGE -> Panel.CustomizeImage(it.id)
                                        TileTypes.MAP -> Panel.CustomizeMap(it.id)
                                        TileTypes.CAR -> Panel.CustomizeCar(it.id)
                                        in WIDGET_OPTIONS -> Panel.CustomizeWidget(it.id)
                                        else -> Panel.CustomizeGauge(it.id)
                                    }
                                },
                                // Todos tienen opciones; el auto 3D, solo con Llantas prendido (las de las llantas).
                                hasSettings = { it.type != TileTypes.CAR || d.hasTpmsSource },
                            ) { p -> TileContent(d, p, apps, car, revision, actions) }
                    }
                }
                if (!rail) bar(false)
            }
            if (railRight) railBar()
          }
        }

        when (val p = panel) {
            null -> Unit
            Panel.Drawer -> DrawerPanel(d, apps, close = { panel = null })
            Panel.Settings, Panel.Updates, Panel.Workshop, Panel.MusicSettings, Panel.TiresSettings -> SettingsPanel(
                d,
                revision = revision,
                close = { panel = null },
                // Acomodar cierra lo que esté en grande (si no, el tablero ni se ve).
                onEdit = { panel = null; musicBig = false; editing = true },
                onTemplates = { panel = Panel.Templates },
                start = when (p) {
                    Panel.Updates -> SettingsSection.SYSTEM
                    Panel.Workshop -> SettingsSection.WORKSHOP
                    Panel.MusicSettings -> SettingsSection.APPS
                    Panel.TiresSettings -> SettingsSection.TIRES
                    else -> SettingsSection.CAR
                },
                onTires = { panel = null; openTires() },
            )
            Panel.Templates -> TemplatesPanel(d.dashboard, close = { panel = null })
            Panel.BarSettings -> BarSettingsPanel(d, close = { panel = null })
            Panel.Wallpaper -> PanelScaffold(stringResource(R.string.wallpaper_title), close = { panel = null }) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Label(stringResource(R.string.wallpaper_help), Typography.body, c.textMuted)
                    WallpaperSettings()
                }
            }
            Panel.AddTile -> AddTilePanel(
                d,
                close = { panel = null },
                onPickApp = { panel = Panel.PickApp(null) },
                onPickGauge = { panel = Panel.PickGauge(null) },
                onTiresSettings = { panel = Panel.TiresSettings },
            )
            is Panel.PickApp -> PickAppPanel(d, apps, p.tileId, close = { panel = p.back })
            is Panel.PickGauge -> PickGaugePanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeGauge -> CustomizeGaugePanel(d, p.tileId, close = { panel = null }, onPickSignal = { panel = Panel.PickGauge(p.tileId) })
            is Panel.CustomizeImage -> CustomizeImagePanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeMap -> CustomizeMapPanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeCar -> CustomizeCarPanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeWidget -> CustomizeWidgetPanel(
                d, p.tileId, apps, cell,
                close = { panel = null },
                onPickApp = { panel = Panel.PickApp(p.tileId, back = p) },
                preview = { t -> TileContent(d, t, apps, car, revision, actions) },
            )
        }

        val big = bigTile
        if (carShown && panel == null && big != null) {
            CarOnly(
                d, car, big, tileBounds[big.id] ?: Rect.Zero, { carProgress.value }, backdrop,
                onTap = { carOnly = false }, onReady = { carReady = true },
            )
        }

        if (tiresShown && panel == null) {
            TiresFull(
                d, { tiresProgress.value },
                onClose = { tiresOpen = false },
                onSettings = { tiresOpen = false; panel = Panel.TiresSettings },
                onReady = { tiresReady = true },
            )
        }

        if (parked) Parked(d, onWake = { parked = false; touched++ })

        // Animación de entrada con tu imagen, una vez por cada vez que se abre LiteDash (no al cambiar de idioma).
        var intro by rememberSaveable { mutableStateOf(!MainActivity.takeRestarted() && d.prefs.logoIntro && d.prefs.welcomeSeen) }
        if (intro) LogoIntro { intro = false }

        if (!d.prefs.welcomeSeen) {
            Welcome(d.prefs, d.dashboard, revision, onFinish = { d.prefs.welcomeSeen = true })
        }
    }
}

/**
 * Estacionado: reloj grande, saludo y clima (o tu imagen, si la elegiste) sobre fondo oscuro.
 * Sale tras [PARK_AFTER_MS] sin tocar el inicio (y sin ir en movimiento); un toque lo quita.
 */
@Composable
private fun Parked(d: Dependencies, onWake: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        // Sin imagen guardada, el reloj.
        if (d.prefs.parkedView == ParkedView.IMAGE && parkedImageShown()) return@Box
        val clock = rememberClock()
        val hello = greeting(d.prefs.driverName)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Siempre en colores oscuros: de noche no deslumbra.
            LiteDashTheme(dark = true, scale = 1f, accent = d.prefs.accentColor) {
                BigTime(clock, Typography.clock.copy(fontSize = 120.sp))
                Label(hello?.let { "$it · ${clock.date}" } ?: clock.date, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1)
                Box(Modifier.padding(top = 12.dp)) { WeatherChip(d.weather) }
            }
        }
    }
}

private const val PARK_AFTER_MS = 5 * 60_000L

/** Más rápido que esto, va manejando (el modo estacionado espera). */
private const val MOVING_KMH = 8f

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
fun dockFavorites(d: Dependencies, apps: List<App>): List<App> {
    d.prefs.dock?.let { keys -> return keys.mapNotNull { d.apps.find(apps, it) } }
    return listOfNotNull(
        // Android Auto / CarPlay primero: mucha gente usa la pantalla con su celular.
        projectionApp(apps),
        d.apps.firstOf(apps, *NAVIGATION_PACKAGES),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.music.preferred?.let { d.apps.firstOf(apps, it) } ?: d.apps.firstOf(apps, *MUSIC_PACKAGES),
        d.apps.firstOf(apps, "com.google.android.youtube"),
        d.apps.firstOf(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.firstOf(apps, "com.android.chrome"),
    )
}

/** Alto de la barra de arriba, y el espacio que deja cuando no muestra nada. */
@Composable
private fun topBarHeight() = if (isShortScreen()) 44.dp else 64.dp
private val TOP_BAR_HIDDEN = 12.dp

@Composable
private fun TopBar(
    editing: Boolean,
    showTime: Boolean,
    driverName: String?,
    weather: WeatherRepository,
    onAdd: () -> Unit,
    onTemplates: () -> Unit,
    onWallpaper: () -> Unit,
    onFinish: () -> Unit,
) {
    val c = LocalColors.current
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editing && !showTime) return Spacer(Modifier.height(TOP_BAR_HIDDEN))
    Row(
        Modifier.fillMaxWidth().height(topBarHeight()).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Label(stringResource(R.string.arrange_your_home), Typography.subtitle, maxLines = 1)
                Label(stringResource(R.string.arrange_hint), Typography.small, c.textMuted, maxLines = 1)
            }
            Button(stringResource(R.string.wallpaper_button), onWallpaper)
            Button(stringResource(R.string.templates), onTemplates)
            Button(stringResource(R.string.add), onAdd)
            Button(stringResource(R.string.done), onFinish, primary = true)
        } else {
            val clock = rememberClock()
            // Con nombre: el saludo primero y la fecha después, más tenue.
            val hello = greeting(driverName)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hello != null) Label(hello, Typography.subtitle, maxLines = 1)
                Label(clock.date, Typography.body, c.textMuted, maxLines = 1)
            }
            WeatherChip(weather)
            StatusIcons()
            Label(clock.full, Typography.subtitle, maxLines = 1)
        }
    }
}

/** Versión nueva encontrada al abrir LiteDash: verla en Ajustes o dejarla para después. */
@Composable
private fun UpdateBanner(updater: Updater, onOpen: () -> Unit) {
    val v = updater.notice ?: return
    Notice(stringResource(R.string.new_version, v.name), Icons.info) {
        Button(stringResource(R.string.update_see), onOpen, primary = true)
        Button(stringResource(R.string.update_later), updater::dismiss)
    }
}

/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun HomePrompt(prefs: Preferences, revision: Int) {
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    val isHome = remember(revision, returns) { isHomeApp(context) }
    if (isHome) return
    Notice(stringResource(R.string.not_home_notice)) {
        Button(stringResource(R.string.make_home), request, primary = true)
        Button(stringResource(R.string.dont_ask), { prefs.dontAskHome = true })
    }
}

/** El asistente de voz del sistema (manos libres primero); null si la pantalla no trae ninguno. */
internal fun voiceIntent(context: Context): Intent? =
    listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

/** Lo que mide la barra de Android de arriba mientras está oculta (0 si se ve). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHiddenHeight(): Dp {
    val density = LocalDensity.current
    val always = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val now = WindowInsets.statusBars.getTop(density)
    return with(density) { (always - now).coerceAtLeast(0).toDp() }
}

/** Tamaño mínimo de la pieza del auto o imagen para ponerle las pastillas de llantas en sus esquinas. */
private val PILLS_MIN_WIDTH = 220.dp
private val PILLS_MIN_HEIGHT = 160.dp

/** Lo más que Llantas espera a que el auto de arriba esté pintado antes de aparecer. */
private const val TIRES_WAIT_MS = 300L

/** Lo que tardan las llantas a pantalla completa en aparecer (y en irse). */
private const val TIRES_FADE_MS = 260

/** Una opción del widget (de fábrica prendida, [WidgetFlags]). */
private fun Tile.flag(key: String) = WidgetFlags.on(data, key)

/**
 * Media pantalla: [content] (el widget en grande) y, en la otra mitad, [side]. Acostada, lado a
 * lado ([sideFirst] = [side] a la izquierda); parada, uno sobre otro ([side] arriba).
 */
@Composable
private fun HalfScreen(side: @Composable () -> Unit, sideFirst: Boolean, vertical: Boolean, content: @Composable () -> Unit) {
    val parts = if (sideFirst) listOf(side, content) else listOf(content, side)
    if (vertical) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (part in parts) Box(Modifier.weight(1f).fillMaxWidth()) { part() }
    } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (part in parts) Box(Modifier.weight(1f).fillMaxHeight()) { part() }
    }
}
