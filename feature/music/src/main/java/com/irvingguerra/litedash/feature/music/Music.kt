package com.irvingguerra.litedash.feature.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import java.util.concurrent.Executors
import com.irvingguerra.litedash.feature.musica.ServicioMusica

/** Recibe los avisos que pasa el [NotificationService] (la app solo mira los de navegación). */
interface NotificationListener {
    /**
     * Alguien los quiere ahora (la app ya está armada). Si no, el servicio no pasa nada: Android
     * lo conecta solo (también tras forzar el cierre) y no hay por qué armar la app por un aviso.
     */
    val wanted: Boolean
    fun onPosted(notification: StatusBarNotification)
    fun onRemoved(notification: StatusBarNotification)

    /** Android acaba de conectar el servicio: ya se pueden pedir los avisos que hay ([NotificationService.active]). */
    fun onConnected() = Unit
}

/** Nombre en inglés del servicio de avisos, que por compatibilidad conserva su nombre original. */
typealias NotificationService = ServicioMusica

private const val STORE = "music"
private const val KEY_LAST_APP = "last_app"
/** Ya se intentó adivinar la app de radio (una sola vez, al estrenar la pieza). */
private const val KEY_SOURCES_GUESSED = "sources_guessed"
/** La pieza Radio prefiere la de internet (true) o la FM de la pantalla. */
private const val KEY_RADIO_INTERNET = "radio_internet"
/** Tu app de música (método App); antes la guardaba el app en sus preferencias (`music_app`). */
private const val KEY_PREFERRED = "preferred_app"
/** Ya se pasó la app de música de las preferencias del app a [KEY_PREFERRED]. */
private const val KEY_PREFERRED_MOVED = "preferred_moved"
/** El método de música elegido ([MusicMode]). */
private const val KEY_MODE = "music_mode"
/** Lo que se espera la carátula que el celular anunció antes de buscarla por título y artista. */
private const val PHONE_ART_WAIT_MS = 3_000L
/** Ya se revisó una vez la app de Bluetooth enlazada ([MusicRepository.fixBluetoothLink]). */
private const val KEY_BT_FIXED = "bt_link_fixed"

/** Palabras con las que se reconoce la app de radio de la pantalla (en el paquete o en el nombre). */
private val RADIO_WORDS = listOf("radio", "fm")

/** Apps de Bluetooth de marca conocidas (chip propio): se prefieren sobre adivinar por nombre. */
private val KNOWN_BT = setOf("com.syu.bt", "com.tw.bt", "com.microntek.bt", "com.clw.btmusic", "com.autochips.bluetooth")

/** Cuánto se espera a que la app despertada empiece a sonar antes de abrirla. */
private const val WAKE_TIMEOUT_MS = 4_000L
/** Tras abrir una radio sin control, cuánto se espera para regresar al inicio (y luego para anotar si sigue el audio). */
private const val RADIO_RETURN_MS = 3_000L
/** Marca de "abriendo TopWay": si se queda puesta, la app se cerró al abrirlo. */
private const val KEY_TOPWAY_OPENING = "topway_opening"
private const val TOPWAY_XT_BIND = "com.tw.service.xt.CommandService.Bind"
/** Lo que tarda el sintonizador de fábrica (TopWay, NWD) en contestar (la estación o que ya tiene el audio). */
private const val FACTORY_ANSWER_MS = 2_000L
/** Lo que LiteDash supone de una app de fábrica (suena / en pausa) deja de valer tras este rato (p. ej. apagaron el auto). */
private const val ASSUME_MAX_MS = 3 * 60 * 60_000L
/** Tras un play a una app de música, cuánto se espera a que suene antes de abrirla. */
private const val PLAY_CHECK_MS = 4_000L
/** Tras abrirla, cuánto se espera para regresar al inicio y darle play otra vez (Spotify tarda en despertar). */
private const val PLAY_RETURN_MS = 4_000L
/** Cuántos eventos guarda el registro de "Enviar un reporte". */
private const val TRACE_SIZE = 60
/** Un aviso (broadcast) de qué suena vale como respaldo durante este rato. */
private const val BROADCAST_FRESH_MS = 10 * 60_000L

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/** Apps de Bluetooth de marca (chip propio, no el de Android): para saber la familia en los reportes. */
private val BRAND_BT_APPS = listOf(
    "com.syu.bt", "com.syu.ms", "com.fyt.bt",           // FYT
    "com.tw.bt", "com.tw.service", "com.ts.bt",         // TopWay
    "com.microntek.bt",                                  // Microntek (MTCD/PX5)
    "com.goodocom.gocsdk",                               // Goodocom gocsdk
    "com.autochips.bluetooth",                           // Autochips (AC8227L…)
    "com.yftech.service.bluetooth",                      // YF Tech (GAC)
)

/**
 * Lo que suena en cualquier app (Spotify, YouTube Music, la radio de la pantalla…)
 * vía las sesiones de medios de Android. Solo escucha mientras alguna pieza de
 * música está en pantalla ([use]/[release] cuentan cuántas): nada de polling.
 */
class MusicRepository(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var users = 0

    /** Lo que ve la pieza Música (todo menos la radio). Atajo de [music]. */
    val song: Song? get() = music.song

    /** La app que la pieza Música está despertando ("Abriendo Spotify…"); null = nada. Atajo de [music]. */
    val starting: String? get() = music.starting

    /** Nombre del celular conectado por Bluetooth a la pantalla; null = ninguno (o sin permiso para verlo). */
    var bluetoothDevice by mutableStateOf<String?>(null)
        private set

    /** Nombre de la memoria USB conectada; null = ninguna. */
    var usbDrive by mutableStateOf<String?>(null)
        private set

    // La última app que sonó (nunca LiteDash): sin app elegida, play la despierta aunque ya no esté abierta.
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * Tu app de música, elegida en Ajustes ([choose]); null = ninguna: la pieza muestra lo que
     * suene. Es lo que sigue el método App ([mode]). Se guarda aquí (una sola verdad).
     */
    val preferred: String? get() = preferredState
    // Estado de Compose: Ajustes resalta la elegida al momento.
    private var preferredState by mutableStateOf(store.getString(KEY_PREFERRED, null))

    /**
     * Hasta la 1.4.115 la app la guardaba el app en sus preferencias ([old]): se pasa aquí una sola vez
     * (después vale lo de aquí, aunque se quite la app).
     */
    fun adoptPreferred(old: String?) {
        if (store.getBoolean(KEY_PREFERRED_MOVED, false)) return
        store.edit().putBoolean(KEY_PREFERRED_MOVED, true).apply()
        if (old != null && preferredState == null) choose(old)
    }

    /**
     * El método de música, uno solo a la vez (Ajustes → Apps → Música): Archivos (el reproductor de
     * LiteDash), Bluetooth (el celular) o App (tu app de música; sin elegir, la que suene). Se guarda.
     */
    var mode: MusicMode
        get() = modeState
        set(v) {
            if (v == modeState) return
            // Solo suena el método nuevo: los demás se callan. Al dejar Archivos, el reproductor propio se
            // detiene (en pausa su sesión seguiría recibiendo el play del sistema y sonaría la USB).
            stopOthers(pkgFor(v))
            if (modeState == MusicMode.FILES && ownSourceNow?.invoke() == SourceKind.USB) ownStop?.invoke()
            modeState = v
            store.edit().putString(KEY_MODE, v.name).apply()
            // Lo que se supo del método anterior (avisos, supuestos) ya no vale.
            music.clear()
            if (users > 0) choose(sessions())
        }
    private var modeState by mutableStateOf(store.getString(KEY_MODE, null)?.let { m -> MusicMode.entries.firstOrNull { it.name == m } } ?: MusicMode.APP)

    /** La app que toca [m]: LiteDash, la del Bluetooth o tu app de música (null = la que suene). */
    private fun pkgFor(m: MusicMode): String? = when (m) {
        MusicMode.FILES -> ownPackage
        MusicMode.BLUETOOTH -> bluetoothApp()
        MusicMode.APP -> preferred
    }

    /** La app del método elegido (la que la pieza Música sigue y despierta); null = la que suene. */
    val modeApp: String? get() = pkgFor(mode)

    /** Archivos sin nada que tocar: la vista abre la biblioteca (lo pone la música en grande mientras se ve). */
    var onFilesEmpty: (() -> Unit)? = null

    /**
     * Apps de la pantalla enlazadas: la de radio FM (Ajustes → Apps → Radio) y, en pantallas con chip de
     * Bluetooth propio, la de Bluetooth; se guardan en [store]. Archivos no se enlaza: es el reproductor de LiteDash.
     */
    var sourceApps by mutableStateOf(SourceKind.entries.filter { it != SourceKind.USB }.mapNotNull { k -> store.getString(k.key, null)?.let { k to it } }.toMap())
        private set

    /**
     * Quien tenía una "app de USB" enlazada (antes de la 1.4.57) la conserva como su app de música,
     * pero solo si la eligió él: las del sistema eran adivinadas y no se suben. Una sola vez.
     */
    fun migrateUsbApp() {
        val old = store.getString(SourceKind.USB.key, null) ?: return
        store.edit().remove(SourceKind.USB.key).apply()
        val system = isSystem(old, unknown = true)
        if (preferred == null && !system) choose(old)
    }

    /** Enlaza (o con null desenlaza) la app de Bluetooth o la de radio. */
    fun setSource(kind: SourceKind, pkg: String?) {
        sourceApps = if (pkg == null) sourceApps - kind else sourceApps + (kind to pkg)
        // Al enlazar otra vez (quizá tras actualizar la app de la pantalla) se vuelve a preguntar su receptor.
        if (pkg != null) receivers.remove(pkg)
        // Lo que se suponía de la app anterior ya no vale (la radio lo olvida en radioChanged()).
        if (kind == SourceKind.BLUETOOTH) music.forget()
        store.edit().putString(kind.key, pkg).apply()
        if (users > 0) loadSourceNotes()
        if (kind == SourceKind.RADIO) radioChanged()
        // Enlazar o quitar la radio cambia qué pieza se queda con cada sesión.
        if (users > 0) choose(sessions())
    }

    private val SourceKind.key get() = "source_" + name.lowercase()

    /**
     * Cambió con qué suena la pieza Radio (se enlazó o quitó la FM, o se pasó entre la FM y la de
     * internet): lo que se supo de la anterior (la estación de TopWay, el play que se
     * le mandó) ya no vale, y TopWay solo escucha mientras la FM sea la radio escogida.
     */
    private fun radioChanged() {
        mainHandler.removeCallbacks(factoryConfirm)
        radio.clear()
        if (users > 0) startFactory()
    }

    /**
     * Pide al reproductor propio que suene esa fuente y avisa en el hilo principal: true = ya
     * suena (o va a sonar), false = no hay nada que tocar (la vista abre la biblioteca).
     */
    var ownPlay: ((SourceKind, (Boolean) -> Unit) -> Unit)? = null

    /** Pausa la USB del reproductor propio y cancela un arranque pendiente (lo pone el app). */
    var ownPause: (() -> Unit)? = null

    /** Qué fuente propia está sonando ahora; null si el reproductor propio no suena. */
    var ownSourceNow: (() -> SourceKind?)? = null

    /** El paquete de LiteDash: el "app" de las fuentes que toca el reproductor propio. */
    val ownPackage: String get() = context.packageName

    fun isOwn(pkg: String) = pkg == ownPackage

    /** La app que toca la radio escogida: LiteDash si es la de internet, si no la FM enlazada (null = ninguna). */
    val radioApp: String? get() = if (radioInternet) ownPackage else sourceApps[SourceKind.RADIO]

    /**
     * La pieza Música necesita el acceso a notificaciones para ver qué suena: con App sí (otras apps)
     * y con Bluetooth de chip propio también; con Archivos o el Bluetooth de Android, no.
     */
    val needsAccess: Boolean
        get() = mode == MusicMode.APP || (mode == MusicMode.BLUETOOTH && !bluetoothBuiltIn)

    /** La pantalla trae el Bluetooth de Android (AVRCP): la fuente Bluetooth no pide app. */
    val bluetoothBuiltIn: Boolean get() = bluetoothService != null

    /** La app que toca el Bluetooth: el propio Android (AVRCP) si lo trae, si no la enlazada. */
    fun bluetoothApp(): String? = bluetoothService?.packageName ?: sourceApps[SourceKind.BLUETOOTH] ?: knownBluetooth

    /** La app de Bluetooth de marca instalada (chip propio: TopWay, FYT, Microntek…), si hay. */
    private val knownBluetooth: String? by lazy {
        KNOWN_BT.firstOrNull(::isInstalled)
    }

    /**
     * Antes se adivinaba la app de Bluetooth por palabras y en algunas pantallas quedó otra (p. ej.
     * ZLink, la de CarPlay, en una TopWay). Si hay una app de Bluetooth de marca conocida y la enlazada
     * no es esa, se cambia por la conocida. Una sola vez.
     */
    fun fixBluetoothLink() {
        if (store.getBoolean(KEY_BT_FIXED, false)) return
        store.edit().putBoolean(KEY_BT_FIXED, true).apply()
        val known = knownBluetooth ?: return
        val linked = sourceApps[SourceKind.BLUETOOTH]
        if (linked != null && linked != known && linked !in KNOWN_BT && linked !in BRAND_BT_APPS) setSource(SourceKind.BLUETOOTH, known)
    }

    /**
     * La radio escogida (Ajustes → Apps → Radio): la de internet de LiteDash (true) o la FM de la
     * pantalla. Una sola a la vez; se guarda.
     */
    var radioInternet: Boolean
        get() = radioInternetState
        set(v) {
            if (v == radioInternetState) return
            // Al pasar a internet, la FM de fábrica suelta el audio (si no, sonarían las dos).
            if (v) factoryFor(radio)?.releaseAudio()
            radioInternetState = v
            store.edit().putBoolean(KEY_RADIO_INTERNET, v).apply()
            radioChanged()
            // Al pasar a la FM, la de internet se calla (si no, su sesión en pausa seguiría mandando).
            if (!v && ownSourceNow?.invoke() == SourceKind.RADIO) ownStop?.invoke()
        }

    /** La sesión del reproductor propio (lo pone el app): se lee directo, sin el permiso de avisos. */
    var ownSession: (() -> MediaSession.Token?)? = null

    /** El reproductor propio creó su sesión: la pieza la toma (sin el permiso de avisos nadie más avisaría). */
    fun ownSessionChanged() {
        mainHandler.post { if (users > 0) choose(sessions()) }
    }

    /** Detiene el reproductor propio (lo pone el app). */
    var ownStop: (() -> Unit)? = null
    private var radioInternetState by mutableStateOf(store.getBoolean(KEY_RADIO_INTERNET, false))

    /**
     * Deja una sola fuente sonando: calla la USB propia (salvo [keep]) y toda sesión de la pieza
     * Música que esté sonando (salvo [keep] y la radio, que vive en su propia pieza). La música
     * "a ciegas" (Bluetooth/FM sin sesión que LiteDash da por sonando) se calla con la tecla de
     * pausa a su receptor.
     */
    private fun stopOthers(keep: String?) {
        if (keep != ownPackage) ownPause?.invoke()
        for (c in sessions()) {
            val p = c.packageName
            if (p == keep || isRadio(p)) continue
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                runCatching { c.transportControls.pause() }
            }
        }
        val blind = music.song?.takeIf { it.blind && it.playing && it.pkg.isNotBlank() }
        if (blind != null && blind.pkg != keep) press(music, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    private val icons = HashMap<String, Bitmap?>()
    // Nombres de las apps: se piden una vez a Android (es una llamada entre procesos).
    private val labels = HashMap<String, String>()

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        }

    fun requestPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    // Celular por Bluetooth y memoria USB: solo se escuchan mientras la música está en pantalla.
    private val devices = DeviceWatcher(context, onBluetooth = ::phoneChanged, onUsb = { usbDrive = it })

    // El Bluetooth de Android como receptor (A2DP sink): si la pantalla trae el servicio AVRCP,
    // LiteDash lee y controla la música del celular sin app externa. Conectado mientras la música
    // esté a la vista (sin celular, su sesión simplemente no trae canción). No depende de poder ver
    // el nombre del celular: sin el permiso BLUETOOTH_CONNECT nunca se conectaba.
    private var avrcp: BluetoothAvrcp? = null
    private var avrcpController: MediaController? = null
    /** El servicio AVRCP del Bluetooth de Android; se busca una vez, sin importar si la música está a la vista. */
    private val bluetoothService: ServiceInfo? by lazy { BluetoothAvrcp.find(context) }

    // El celular llegó o se fue: la música Bluetooth que se suponía sonando ya no suena.
    private fun phoneChanged(name: String?) {
        bluetoothDevice = name
        if (name != null) {
            avrcp?.connect()
            return
        }
        val app = bluetoothApp() ?: return
        for (lens in listOf(music, radio)) if (lens.assumedPkg == app) lens.forget()
        if (users > 0) choose(sessions())
    }

    /** Si la pantalla trae el Bluetooth de Android (AVRCP), se prepara para leer la música del celular. */
    private fun startAvrcp() {
        // Ya existe (la música se escondió y volvió): stop() solo lo desconectó, se vuelve a conectar.
        avrcp?.let { it.connect(); return }
        val service = bluetoothService ?: return
        avrcp = BluetoothAvrcp(context, service, ::onAvrcpController)
        avrcp?.connect()
    }

    /**
     * El controlador AVRCP ya (des)conectó. Su sesión no siempre llega por las sesiones activas (sin
     * el permiso de avisos, nunca): cuando cambia de estado y ninguna pieza la tiene, se vuelve a repartir.
     */
    private fun onAvrcpController(controller: MediaController?) {
        mainHandler.post {
            avrcpController?.unregisterCallback(avrcpWatch)
            avrcpController = controller
            avrcpLast = controller?.playbackState?.state
            controller?.registerCallback(avrcpWatch, mainHandler)
            if (users > 0) choose(sessions())
        }
    }

    private var avrcpLast: Int? = null
    private val avrcpWatch = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == avrcpLast) return
            avrcpLast = state?.state
            avrcpRechoose()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = avrcpRechoose()
    }

    /** Solo si la pieza podría tomarla (Bluetooth, o App sin app elegida) y aún no la tiene: cada vuelta es IPC. */
    private fun avrcpRechoose() {
        if (users == 0 || !(mode == MusicMode.BLUETOOTH || (mode == MusicMode.APP && preferred == null))) return
        val token = avrcpController?.sessionToken
        if (listOf(music, radio).none { it.controller?.sessionToken == token }) choose(sessions())
    }

    fun use() {
        users++
        if (users == 1) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Llamar al volver a la app: si se acaba de dar el permiso, empieza a escuchar las sesiones. */
    fun recheck() {
        if (users > 0 && !listening) listen()
    }

    // Ya escucha las sesiones de medios (con el permiso de avisos).
    private var listening = false

    /**
     * Respaldo de "qué suena" para apps sin sesión de medios (la música Bluetooth de muchas
     * pantallas chinas): sus avisos (broadcasts). La radio enlazada va a su pieza; lo demás, a Música.
     */
    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val extras = i.extras.toMap()
            trace("aviso ${i.action} [${extras.keys.joinToString()}]")
            val meta = BroadcastMeta.parse(i.action ?: return, extras) ?: return
            val fm = meta.pkg != null && meta.pkg == sourceApps[SourceKind.RADIO]
            // La FM avisando mientras la pieza Radio está en internet: no es lo que se escogió.
            if (fm && radioInternet) return
            // A la música, solo lo del método escogido: con Archivos no se cuela el Bluetooth de la pantalla.
            if (!fm && !musicHears(meta)) return
            (if (fm) radio else music).heard(meta)
        }
    }

    /** ¿El aviso [meta] es del método de música escogido? Bluetooth: los del celular; App: la elegida o cualquiera. */
    private fun musicHears(meta: BroadcastMeta): Boolean = when (mode) {
        MusicMode.FILES -> false
        MusicMode.BLUETOOTH -> meta.bluetooth || meta.pkg == bluetoothApp()
        MusicMode.APP -> modeApp == null || meta.pkg == modeApp
    }

    private fun start() {
        devices.start()
        startAvrcp()
        // Son avisos de otras apps: el receptor debe aceptar avisos de fuera. Cualquier app podría mandar
        // un título falso; solo se muestra (sin sesión de por medio) y la sesión real siempre manda.
        val filter = IntentFilter().apply { BroadcastMeta.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
        NotificationService.media = notifications
        loadSourceNotes()
        startFactory()
        if (!store.getBoolean(KEY_SOURCES_GUESSED, false)) {
            store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
            Thread(::guessSources, "music-sources").start()
        }
        listen()
        // Sin permiso de avisos, igual se ven la sesión propia (Archivos) y la del Bluetooth de Android;
        // lo demás, lo último avisado por broadcast.
        if (!listening) choose(sessions())
    }

    private fun listen() {
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            listening = true
            choose(sessions())
        }
    }

    private fun stop() {
        devices.stop()
        avrcp?.disconnect()
        avrcpController?.unregisterCallback(avrcpWatch)
        avrcpController = null
        runCatching { context.unregisterReceiver(broadcasts) }
        if (NotificationService.media === notifications) NotificationService.media = null
        if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        listening = false
        // Cerrado pero se queda (con la última estación) para el reporte; al volver se abre otro.
        factoryRun++
        factory?.close()
        music.detach()
        radio.detach()
    }

    /**
     * Perfil de la radio de fábrica ([FactoryRadio]) cuando la FM enlazada no tiene receptor ni servicio:
     * TopWay (`com.tw.radio`, por su enlace TWUtil; si alguna vez la app se cerró al abrirlo, no se vuelve a
     * intentar) o NWD (`com.nwd.radio`, por los ajustes y avisos del sistema).
     */
    private var factory: FactoryRadio? = null
    /** Sube en cada arranque o parada: un perfil que termina de abrir tarde se descarta. */
    private var factoryRun = 0
    /**
     * Los perfiles se abren de uno en uno: si se piden dos seguidos (la música se esconde y vuelve), el
     * segundo espera al primero en vez de ver la marca de TopWay a medio abrir y creer que la app se cerró.
     */
    private val factoryThread = Executors.newSingleThreadExecutor { Thread(it, "factory-radio") }

    /** Se abre en otro hilo (reflexión, el driver y el disco); se publica en el principal. */
    @SuppressLint("ApplySharedPref") // commit a propósito: debe quedar escrito antes de abrir
    private fun startFactory() {
        factory?.close()
        factory = null
        val run = ++factoryRun
        // Solo si la FM es la radio escogida (no la de internet).
        val pkg = radioApp?.takeUnless(::isOwn) ?: return
        factoryThread.execute {
            if (run != factoryRun || !isSilent(pkg)) return@execute
            val log: (String) -> Unit = { e -> mainHandler.post { trace(e) } }
            val onState: (BroadcastMeta) -> Unit = { radio.heard(it.copy(pkg = pkg)) }
            if (pkg.startsWith(NwdRadio.PACKAGE)) {
                val nwd = NwdRadio(context, onState, log)
                mainHandler.post { if (run == factoryRun && nwd.open()) factory = nwd }
                return@execute
            }
            if (!TopWayRadio.available) return@execute
            if (store.getBoolean(KEY_TOPWAY_OPENING, false)) return@execute log("topway apagado: la app se cerró al abrirlo")
            // Marcado antes de abrir (y guardado ya): si el firmware tumba la app, la próxima vez no se abre.
            store.edit().putBoolean(KEY_TOPWAY_OPENING, true).commit()
            val tw = TopWayRadio(onState = onState, trace = log)
            val opened = tw.open()
            store.edit().putBoolean(KEY_TOPWAY_OPENING, false).apply()
            mainHandler.post { if (opened && run == factoryRun) factory = tw else tw.close() }
        }
    }

    /** Sin receptor del botón de play ni servicio de música: no hay cómo hablarle (sin caché: puede ir en otro hilo). */
    private fun isSilent(pkg: String) = mediaButtonReceivers(pkg).isEmpty() && browserService(pkg) == null

    /** La radio de la pieza se controla por el perfil de su marca. */
    private fun factoryFor(lens: Lens): FactoryRadio? = factory?.takeIf { lens === radio && it.open }

    /**
     * Las sesiones activas y, de las apps enlazadas (Bluetooth, FM), la de su aviso de música si
     * Android no la cuenta como activa (algunas pantallas solo la dejan ver por ahí).
     */
    private fun sessions(): List<MediaController> {
        val active = runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())
        val extra = sourceNotes.values.filter { n -> active.none { it.packageName == n.packageName } }.mapNotNull { n ->
            n.sessionToken()?.let { runCatching { MediaController(context, it) }.getOrNull() }
        }
        val own = ownSession?.invoke()?.takeIf { t -> (active + extra).none { it.sessionToken == t } }
            ?.let { runCatching { MediaController(context, it) }.getOrNull() }
        val all = active + extra + listOfNotNull(own)
        // El Bluetooth de Android (AVRCP) no sale entre las sesiones activas: se agrega.
        val av = avrcpController ?: return all
        return if (all.any { it.packageName == av.packageName }) all else all + av
    }

    /**
     * El aviso de música de cada app enlazada (paquete → aviso), al día con lo que pasa el servicio:
     * así no se le pide a Android la lista de todos los avisos (con sus imágenes) en cada lectura.
     * Solo en el hilo principal.
     */
    private val sourceNotes = HashMap<String, StatusBarNotification>()

    /** Vuelve a leer los avisos de las apps enlazadas (al empezar, al conectarse el servicio o al enlazar otra). */
    private fun loadSourceNotes() {
        sourceNotes.clear()
        val apps = sourceApps.values.toSet()
        if (apps.isEmpty()) return
        NotificationService.active().filter { it.packageName in apps && it.isMedia() }.forEach { sourceNotes[it.packageName] = it }
    }

    /** Título y texto del aviso de música de [pkg]; null si no tiene. */
    private fun notified(pkg: String): Pair<String, String>? {
        val extras = sourceNotes[pkg]?.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        return if (title.isEmpty()) null else title to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    }

    /**
     * Si una app enlazada cambia su aviso de música (otra sesión u otra canción), se vuelve a mirar.
     * Los avisos que no cambian eso (algunas apps lo actualizan cada segundo con el avance) no hacen nada.
     */
    private val notifications = object : NotificationListener {
        override val wanted: Boolean get() = users > 0
        override fun onPosted(notification: StatusBarNotification) = changed(notification, removed = false)
        override fun onRemoved(notification: StatusBarNotification) = changed(notification, removed = true)
        override fun onConnected() {
            mainHandler.post {
                if (users == 0) return@post
                loadSourceNotes()
                choose(sessions())
            }
        }

        private fun changed(n: StatusBarNotification, removed: Boolean) {
            mainHandler.post {
                val pkg = n.packageName
                if (users == 0 || pkg !in sourceApps.values) return@post
                val before = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                when {
                    !removed && n.isMedia() -> sourceNotes[pkg] = n
                    sourceNotes[pkg]?.key == n.key -> sourceNotes.remove(pkg)
                }
                val after = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                if (before == after) return@post
                trace("aviso de música ${if (after == null) "quitado" else "nuevo"} $pkg")
                choose(sessions())
            }
        }

        private fun notifiedTitle(n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    /** ¿[pkg] es la radio: la app FM enlazada de la pantalla o LiteDash tocando radio por internet? */
    fun isRadio(pkg: String): Boolean =
        pkg == sourceApps[SourceKind.RADIO] || (isOwn(pkg) && ownSourceNow?.invoke() == SourceKind.RADIO)

    /** La pieza Música: todo menos la radio; sigue la app del método elegido ([modeApp]) o, sin ella, lo que suene. */
    val music = Lens(accepts = { !isRadio(it) }, fixed = { modeApp }, remembersLast = true, anyApp = { mode == MusicMode.APP })

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false, anyApp = { true })

    init {
        music.onWake = ::resume
        radio.onWake = { resumeRadio() }
    }

    /**
     * Lo que suena en el celular por LiteDash Connect (Link); null = nada o sin conexión. Entra en el método
     * Bluetooth (y en App sin app elegida o con la de Bluetooth, si no suena nada en la pantalla) y manda
     * sobre AVRCP y los avisos de la marca: el dato sale del celular, igual en todas las pantallas.
     */
    private var phoneSong: Song? = null
    /** El celular tiene carátula y llega aparte: mientras, no se busca en iTunes. */
    private var phoneArtComing = false

    /** Manda play, pausa, siguiente, anterior o salto (ms) al celular; falso si ya no hay enlace. */
    var phoneControl: ((String, Long) -> Boolean)? = null

    fun fromPhone(song: Song?, artComing: Boolean) {
        phoneSong = song
        phoneArtComing = artComing
        // Si la carátula anunciada no llega pronto, se busca como cualquier canción por Bluetooth.
        mainHandler.removeCallbacks(artLate)
        if (artComing) mainHandler.postDelayed(artLate, PHONE_ART_WAIT_MS)
        if (users > 0) music.read()
    }

    private val artLate = Runnable {
        phoneArtComing = false
        if (users > 0) music.read()
    }

    /** La canción del celular, si toca mostrarla en [lens]. */
    private fun phoneFor(lens: Lens): Song? {
        val s = phoneSong?.takeIf { lens === music } ?: return null
        return when (mode) {
            MusicMode.FILES -> null
            // El celular en pausa no le gana a otra música por Bluetooth que sí suena (otro celular).
            MusicMode.BLUETOOTH -> s.takeIf { it.playing || lens.controller?.playbackState?.state != PlaybackState.STATE_PLAYING }
            MusicMode.APP -> {
                val app = modeApp
                if (app != null && app != bluetoothApp()) return null
                // Algo de la pantalla suena (que no sea el Bluetooth): eso es lo que se escucha.
                val local = lens.controller?.takeIf { it.packageName != avrcpPackage && it.packageName != bluetoothApp() }
                s.takeIf { local?.playbackState?.state != PlaybackState.STATE_PLAYING && (s.playing || local == null) }
            }
        }
    }

    /** Cada pieza elige su sesión de la misma lista. */
    private fun choose(list: List<MediaController>) {
        // Otra app empezó a sonar: lo que LiteDash suponía de la de Bluetooth o FM ya no vale.
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }
        for (lens in listOf(music, radio)) if (lens.assumedPkg != null && playing.any { it != lens.assumedPkg }) lens.forget()
        music.choose(list)
        radio.choose(list)
    }

    /**
     * Una "vista" de las sesiones de medios: la pieza Música y la pieza Radio miran la misma
     * lista pero cada una se queda con lo suyo ([accepts]); [fixed] es la app fija (la elegida
     * en Ajustes) y [remembersLast] si guarda la última app que sonó (para que play la despierte).
     */
    @Stable
    inner class Lens internal constructor(
        private val accepts: (String) -> Boolean,
        private val fixed: () -> String?,
        private val remembersLast: Boolean,
        /** Sin app fija, ¿vale cualquiera que suene? Solo con el método App (Archivos y Bluetooth no muestran otras). */
        private val anyApp: () -> Boolean,
    ) {
        var song by mutableStateOf<Song?>(null)
            private set

        /** La app que esta pieza está despertando ("Abriendo Spotify…"); null = nada. */
        var starting by mutableStateOf<String?>(null)
            internal set

        /** El plazo del último despertar ([wake]). */
        internal var wakeTimeout = Runnable {}

        internal var controller: MediaController? = null

        /**
         * La app de la pantalla (Bluetooth, FM) a la que LiteDash le mandó play o pausa sin que
         * una sesión diga qué hace: se supone que obedeció ([assumedPlaying]) hasta que otra app
         * suene o se cambie de fuente. Así el botón pasa a pausa y la pieza muestra la fuente.
         */
        internal var assumedPkg: String? = null
        private var assumedPlaying = false
        private var assumedAt = 0L

        internal fun assume(pkg: String, playing: Boolean) {
            assumedPkg = pkg
            assumedPlaying = playing
            assumedAt = SystemClock.elapsedRealtime()
            read()
            if (song != null) starting = null
        }

        /** Lo que se supone de [pkg] (suena o no), si se le mandó play o pausa hace menos de [ASSUME_MAX_MS]. */
        private fun assumedFor(pkg: String?): Boolean? =
            assumedPlaying.takeIf { pkg != null && pkg == assumedPkg && SystemClock.elapsedRealtime() - assumedAt < ASSUME_MAX_MS }

        internal fun forget() {
            if (assumedPkg == null) return
            assumedPkg = null
            read()
        }

        /** Olvida todo lo que se supo sin sesión (avisos y supuestos): cambió la app de la pieza. */
        internal fun clear() {
            mainHandler.removeCallbacks(expire)
            heard = null
            assumedPkg = null
            starting = null
            read()
        }

        /** Lo último que avisó una app por broadcast ([BroadcastMeta]) y cuándo; respaldo sin sesión. */
        private var heard: BroadcastMeta? = null
        private var heardAt = 0L
        private val expire = Runnable {
            if (users == 0) return@Runnable
            // La estación de TopWay solo llega al cambiar: se vuelve a pedir y se lee cuando contesta
            // (si contesta, el aviso nuevo ya la dejó vigente y no parpadea).
            val tw = factoryFor(this) ?: return@Runnable read()
            tw.refresh()
            mainHandler.postDelayed({ if (users > 0) read() }, FACTORY_ANSWER_MS)
        }

        internal fun heard(meta: BroadcastMeta) {
            heard = meta.over(heard)
            heardAt = SystemClock.elapsedRealtime()
            // Pasado el rato, deja de mostrarse aunque no llegue otro aviso.
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, BROADCAST_FRESH_MS)
            // Una sesión con canción manda; una vacía (Bluetooth de fábrica) no dice nada: el aviso sí.
            if (controller?.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty()) return
            read()
            if (song != null) starting = null
        }

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = read()
            override fun onPlaybackStateChanged(state: PlaybackState?) = read()
            override fun onSessionDestroyed() = this@MusicRepository.choose(sessions())
        }

        // Con app fija, solo esa (salvo el reproductor propio sonando). Si no: la que suena; si ninguna, la más reciente con canción
        // (Android las da en ese orden), para no quedarse con sesiones vacías.
        internal fun choose(all: List<MediaController>) {
            val list = all.filter { accepts(it.packageName) }
            val p = fixed()
            val withTitle = list.filter { it.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty() }
            // El reproductor propio sonando (o cargando) siempre se ve: lo puso a sonar el usuario.
            val own = list.firstOrNull { isOwn(it.packageName) && it.busy() }
            val chosen = if (own != null) {
                own
            } else if (p != null) {
                // Varias apps de fábrica dejan muchas sesiones (las TopWay, `com.tw.bt`, acumulan vacías):
                // la que suena con canción, si no la que tiene canción, si no la que suena, si no cualquiera.
                val mine = list.filter { it.packageName == p }
                mine.firstOrNull { it in withTitle && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: mine.firstOrNull { it in withTitle }
                    ?: mine.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: mine.firstOrNull()
            } else if (!anyApp()) {
                null
            } else {
                // La del Bluetooth de Android sin celular sonando es una sesión vacía: no cuenta como "cualquiera".
                withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: withTitle.firstOrNull()
                    ?: list.firstOrNull { it.packageName != avrcpPackage }
            }
            if (chosen?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            read()
        }

        internal fun detach() {
            mainHandler.removeCallbacks(expire)
            controller?.unregisterCallback(callback)
            controller = null
            song = null
        }

        internal fun read() {
            phoneFor(this)?.let { phone ->
                song = if (phone.artwork != null || phoneArtComing) phone else withCover(phone)
                starting = null
                return
            }
            val c = controller
            val m = c?.metadata
            if (c == null) {
                song = fromBroadcast() ?: fromAssumed()
                return
            }
            // La sesión de LiteDash cambia de USB a radio (o al revés): que la tome la otra pieza.
            if (!accepts(c.packageName)) return this@MusicRepository.choose(sessions())
        val state = c.playbackState
        val reported = state?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_PAUSED || it == PlaybackState.STATE_BUFFERING || it == PlaybackState.STATE_CONNECTING }
        // Una sesión que no dice si suena (con o sin título): lo que LiteDash le mandó.
        val assumed = if (reported) null else assumedFor(c.packageName)
        val playing = assumed ?: (state?.state == PlaybackState.STATE_PLAYING)
        val app = appLabel(c.packageName)
        val shown = notified(c.packageName)
        val named = m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifBlank { shown?.first.orEmpty() }
        // Sesión vacía que ni siquiera dice si suena (la abren las apps de Bluetooth y FM de fábrica
        // solo para atrapar el volante): manda lo que avisó la app o lo que LiteDash le pidió.
        if (named.isBlank() && !reported) {
            val blind = fromBroadcast() ?: fromAssumed()
            if (blind != null) {
                song = blind
                return
            }
        }
        // Sin título y en pausa = apps que solo dejan la sesión abierta: "nada sonando".
        // Sin título pero sonando = la música por Bluetooth de muchas pantallas (el
        // celular no manda la canción): se muestra con el nombre de la app y sus controles,
        // y sigue en pantalla al pausarla para poder darle play otra vez.
        if (named.isBlank() && !playing && song?.pkg != c.packageName) {
            song = null
            return
        }
        val title = named.ifBlank { app }
        starting = null
        if (remembersLast && playing && !isOwn(c.packageName) && c.packageName != avrcpPackage && c.packageName != lastSavedApp) {
            lastSavedApp = c.packageName
            store.edit().putString(KEY_LAST_APP, c.packageName).apply()
        }
        song = withCover(Song(
            title = title,
            artist = m.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).ifBlank { shown?.second.orEmpty() },
            app = app,
            artwork = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = playing,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = state?.position ?: 0,
            readAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed ?: 1f,
            pkg = c.packageName,
            seekable = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            actions = state?.customActions.orEmpty().map {
                MediaAction(it.action, it.name.toString(), icon(c.packageName, it.icon))
            },
            source = sourceOf(c.packageName),
            buffering = state?.state == PlaybackState.STATE_BUFFERING || state?.state == PlaybackState.STATE_CONNECTING,
            error = state?.takeIf { it.state == PlaybackState.STATE_ERROR }?.errorMessage?.toString(),
            blind = assumed != null,
        ))
        }

        /**
         * Sin sesión, lo último avisado por broadcast si es reciente: título, artista y estado
         * (sin carátula ni avance). Sin título, el nombre de la app (o "Bluetooth"), como la sesión.
         */
        private fun fromBroadcast(): Song? {
            val h = heard?.takeIf { SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS } ?: return null
            val pkg = h.pkg ?: sourceApps[SourceKind.BLUETOOTH]?.takeIf { h.bluetooth }
            val app = pkg?.let(::appLabel).orEmpty()
            // Si LiteDash le mandó play o pausa después del último aviso, eso manda; si el aviso no dice, suena.
            val sent = assumedFor(pkg ?: assumedPkg)?.takeIf { assumedAt > heardAt }
            val playing = sent ?: h.playing ?: true
            if (h.title == null && !playing) return null
            val title = h.title ?: app.ifBlank { if (h.bluetooth) context.getString(R.string.source_bluetooth) else return null }
            return withCover(blindSong(title, h.artist.orEmpty(), app, playing, pkg.orEmpty(), pkg?.let(::sourceOf) ?: SourceKind.BLUETOOTH.takeIf { h.bluetooth }))
        }

        /** Lo que LiteDash supone de la app a la que le mandó play o pausa ([assume]). */
        private fun fromAssumed(): Song? {
            val pkg = assumedPkg ?: return null
            val playing = assumedFor(pkg) ?: return null
            val kind = sourceOf(pkg)
            val app = appLabel(pkg)
            val fallback = context.getString(if (kind == SourceKind.RADIO) R.string.source_radio else R.string.source_bluetooth)
            return blindSong(app.ifBlank { fallback }, "", app, playing, pkg, kind)
        }

        /** Canción "a ciegas" (sin sesión que la describa): sin carátula, avance ni acciones. */
        private fun blindSong(title: String, artist: String, app: String, playing: Boolean, pkg: String, source: SourceKind?) = Song(
            title = title,
            artist = artist,
            app = app,
            artwork = null,
            playing = playing,
            durationMs = 0,
            positionMs = 0,
            readAt = SystemClock.elapsedRealtime(),
            speed = 1f,
            pkg = pkg,
            seekable = false,
            actions = emptyList(),
            source = source,
            buffering = false,
            error = null,
            blind = true,
        )

        /**
         * Carátula de respaldo para la música por Bluetooth: si la sesión (o el aviso) no trajo
         * carátula pero sí título y artista, se toma la ya resuelta ([CoverArt.cached], en memoria)
         * o se busca en iTunes ([CoverArt.fetch], que avisa en el hilo principal solo si sigue
         * siendo la misma canción). Fuera de Bluetooth no se busca: su carátula ya llega sola.
         */
        private fun withCover(s: Song): Song {
            if (s.artwork != null || s.source != SourceKind.BLUETOOTH) return s
            val title = s.title
            val artist = s.artist
            if (title.isBlank() || artist.isBlank()) return s
            val cached = CoverArt.cached(title, artist)
            if (cached == null) {
                CoverArt.fetch(context, title, artist) { bmp ->
                    val cur = song
                    if (bmp != null && cur != null && cur.artwork == null && cur.title == title && cur.artist == artist) {
                        song = cur.copy(artwork = bmp)
                    }
                }
                return s
            }
            return s.copy(artwork = cached)
        }

        /**
         * Play o pausa; sin nada sonando, se despierta lo que toque ([onWake]). Con una sesión
         * que obedece, por la sesión. Si no (la música Bluetooth y la radio FM de muchas
         * pantallas: sin sesión, vacía o que no anuncia play), como el botón del volante pero
         * explícito: PAUSA si se cree que suena y PLAY si no (con play/pausa alternado, varias
         * apps de fábrica no hacen nada); y LiteDash recuerda que lo mandó ([assume]).
         */
        fun toggle() {
            val s = song ?: return onWake()
            if (s.phone && phone(if (s.playing) "pause" else "play")) {
                // Sin esperar al celular: el botón cambia ya (también lo guardado, para que otra lectura no lo
                // regrese) y su siguiente aviso lo confirma.
                val now = SystemClock.elapsedRealtime()
                phoneSong = phoneSong?.copy(playing = !s.playing, positionMs = s.currentPosition(), readAt = now)
                song = s.copy(playing = !s.playing, positionMs = s.currentPosition(), readAt = now)
                return
            }
            factoryFor(this)?.let { f ->
                if (!s.playing) return onWake()
                trace("${f.tag} pausa")
                mainHandler.removeCallbacks(factoryConfirm)
                return f.releaseAudio()
            }
            val t = controller?.transportControls?.takeUnless { keysOnly(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) }
            if (!s.blind && t != null) {
                if (s.playing) return t.pause()
                if (this === music) controller?.packageName?.let(::watchPlay)
                if (controller?.packageName == avrcpPackage) ensureBluetoothSound()
                return t.play()
            }
            val play = !s.playing
            val pkg = target(this)
            if (!press(this, if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)) return onWake()
            if (pkg != null && !isOwn(pkg)) assume(pkg, play)
        }

        /**
         * Sin sesión que obedezca, como los botones del volante (a la app de la pieza o al
         * sistema de audio). En la radio FM, siguiente/anterior es lo que la app haga con esas
         * teclas (casi siempre la estación guardada que sigue o buscar la siguiente).
         */
        fun next() {
            if (song?.phone == true && phone("next")) return
            factoryFor(this)?.let { trace("${it.tag} siguiente"); return it.next() }
            controller?.transportControls?.takeUnless { song?.blind == true || keysOnly(PlaybackState.ACTION_SKIP_TO_NEXT) }?.skipToNext() ?: press(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun previous() {
            if (song?.phone == true && phone("prev")) return
            factoryFor(this)?.let { trace("${it.tag} anterior"); return it.previous() }
            controller?.transportControls?.takeUnless { song?.blind == true || keysOnly(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }?.skipToPrevious() ?: press(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        /**
         * La radio FM tiene sesión pero no anuncia [action] (varias FM de pantalla publican la
         * sesión solo para mostrar la estación): mejor la tecla a su receptor, como el volante.
         * La música no: sus apps sí atienden la sesión.
         */
        private fun keysOnly(action: Long): Boolean {
            val c = controller ?: return false
            if (this !== radio || isOwn(c.packageName)) return false
            return (c.playbackState?.actions ?: 0L) and action == 0L && canKey(this)
        }
        fun seekTo(ms: Long) {
            val s = song
            if (s?.phone == true && phone("seek", ms)) {
                val now = SystemClock.elapsedRealtime()
                phoneSong = phoneSong?.copy(positionMs = ms, readAt = now)
                song = s.copy(positionMs = ms, readAt = now)
                return
            }
            controller?.transportControls?.seekTo(ms)
        }

        private fun phone(action: String, ms: Long = 0) = phoneControl?.invoke(action, ms) == true
        fun action(a: MediaAction) = controller?.transportControls?.sendCustomAction(a.id, null)

        /** Qué hacer con play sin sesión: la música despierta la última app; la radio, la FM o la de internet. */
        internal var onWake: () -> Unit = {}
    }

    /** La fuente a la que está enlazada [pkg] (o la que toca LiteDash); null = una app normal. */
    fun sourceOf(pkg: String): SourceKind? = when {
        isOwn(pkg) -> ownSourceNow?.invoke()
        pkg == avrcpPackage || pkg == knownBluetooth -> SourceKind.BLUETOOTH
        else -> sourceApps.entries.firstOrNull { it.value == pkg }?.key
    }

    /** Paquete del servicio AVRCP del Bluetooth de Android; null si la pantalla no lo trae. */
    private val avrcpPackage: String? get() = bluetoothService?.packageName

    private fun appLabel(pkg: String) = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
    }

    // Los íconos de las acciones viven en los recursos de la otra app; se leen una vez.
    private fun icon(pkg: String, id: Int): Bitmap? = icons.getOrPut("$pkg:$id") {
        runCatching {
            val d = context.packageManager.getResourcesForApplication(pkg).getDrawable(id, null)
            val side = (32 * context.resources.displayMetrics.density).toInt()
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bmp ->
                d.setBounds(0, 0, side, side)
                d.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    // Atajos de la pieza Música (el volante y la pieza usan estos).
    fun toggle() = music.toggle()
    fun next() = music.next()
    fun previous() = music.previous()
    fun seekTo(ms: Long) = music.seekTo(ms)
    fun action(a: MediaAction) = music.action(a)

    /** Nombre de la app elegida en Ajustes ("" si no hay). */
    fun preferredName(): String = preferred?.let(::appLabel).orEmpty()

    /** Nombre de la app [pkg] como lo muestra Android ("" si no está). */
    fun appName(pkg: String): String = appLabel(pkg)

    /** Nombre de la app enlazada a [kind] ("" si no hay). */
    fun sourceName(kind: SourceKind): String = sourceApps[kind]?.let(::appLabel).orEmpty()

    /** Elige tu app de música (o ninguna con null) y la guarda. */
    fun choose(pkg: String?) {
        // Lo que se supo de la app anterior (avisos, supuestos) no es de la nueva.
        if (pkg != preferredState) music.clear()
        preferredState = pkg
        store.edit().putString(KEY_PREFERRED, pkg).apply()
        if (users > 0) choose(sessions())
    }

    /** La última app que sonó, solo con el método App (con Archivos o Bluetooth no se despierta otra). */
    private fun lastApp(): String? = if (mode == MusicMode.APP) store.getString(KEY_LAST_APP, null) else null

    /** La app a la que va el play (o una tecla) de [lens] cuando no hay sesión que lo reciba. */
    private fun target(lens: Lens): String? =
        // Lo que se supo sin sesión (aviso o supuesto) manda: la sesión elegida puede ser otra app vacía.
        lens.song?.takeIf { it.blind }?.pkg?.ifEmpty { null } ?: lens.controller?.packageName ?: lens.song?.pkg?.ifEmpty { null }
            ?: if (lens === radio) radioApp else modeApp ?: lastApp()

    /**
     * Que suene lo tocado, la app elegida o, sin ninguna, la última que sonó. Con una canción
     * cargada (en pausa), play directo. Si no, se despierta la app ([wake]): su receptor del
     * botón de play o su sesión, y su servicio de música, como Android Auto; sin ninguno, el
     * botón por el sistema de audio, que Android lleva a la última app de música. Si no suena,
     * [watchPlay] abre la app, regresa y le vuelve a dar play. Mientras, [starting] dice cuál se
     * está abriendo.
     */
    fun resume() {
        val pkg = modeApp ?: music.controller?.packageName ?: lastApp()
        val session = music.controller?.takeIf { song != null && (pkg == null || it.packageName == pkg) }
        if (session != null) {
            watchPlay(session.packageName)
            if (session.packageName == avrcpPackage) ensureBluetoothSound()
            return session.transportControls.play()
        }
        if (pkg == null) return systemPlay()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.USB) { ok ->
            if (!ok) onFilesEmpty?.invoke()
            if (users > 0) choose(sessions())
        } ?: Unit
        watchPlay(pkg)
        wake(pkg, music)
    }

    /**
     * Apps como Spotify, dormidas en segundo plano, a veces dicen que suenan y no suenan hasta abrirlas.
     * Tras un play a una app de música normal (no Bluetooth ni FM: esas suenan por fuera de Android), si en
     * [PLAY_CHECK_MS] Android no tiene audio y la pieza no está en pausa, se abre la app, se regresa al
     * inicio y se le vuelve a dar play.
     */
    private fun watchPlay(pkg: String) {
        if (isOwn(pkg) || sourceOf(pkg) != null) return
        playPkg = pkg
        mainHandler.removeCallbacks(playCheck)
        mainHandler.removeCallbacks(returnAndPlay)
        mainHandler.postDelayed(playCheck, PLAY_CHECK_MS)
    }

    private var playPkg: String? = null
    private val playCheck = Runnable {
        val pkg = playPkg ?: return@Runnable
        // En pausa = la pausaron (solo si la pieza muestra esa misma app).
        if (users == 0 || musicActive() || music.song?.takeIf { it.pkg == pkg }?.playing == false) return@Runnable trace("play $pkg: audio=${yes(musicActive())}")
        music.starting = null
        val over = open(pkg)
        trace("play $pkg: no suena, se abre${if (over) " y se regresa" else ""}")
        if (!over) return@Runnable
        mainHandler.postDelayed(returnAndPlay, PLAY_RETURN_MS)
    }
    private val returnAndPlay = Runnable {
        val pkg = playPkg ?: return@Runnable
        returnHome?.invoke()
        if (musicActive()) return@Runnable trace("regreso: $pkg ya suena")
        val session = sessions().firstOrNull { it.packageName == pkg }
        val by = when {
            session != null -> runCatching { session.transportControls.play() }.let { "sesión" }
            buttonPlay(pkg) -> "receptor"
            else -> "nada"
        }
        trace("regreso: play otra vez a $pkg por $by")
    }

    /**
     * Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla
     * o la radio por internet de LiteDash. Sin con qué (sin app enlazada ni estación), [onIdle]:
     * la pieza abre sus ajustes o la lista de estaciones.
     */
    fun resumeRadio(onIdle: () -> Unit = {}) {
        val session = radio.controller?.takeIf { radio.song != null }
        if (session != null) return session.transportControls.play()
        val pkg = radioApp ?: return onIdle()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.RADIO) { ok -> if (!ok) onIdle() } ?: Unit
        wake(pkg, radio)
    }

    /** Abre la app de radio de la pantalla (si hay una enlazada). */
    fun openRadioApp() {
        sourceApps[SourceKind.RADIO]?.let(::open)
    }

    /** Abre la app de Bluetooth (la enlazada o la de marca; para vincular en pantallas con chip propio). */
    fun openBluetoothApp() {
        bluetoothApp()?.let(::open)
    }

    /**
     * Despierta a [pkg] para que suene en [lens], por un solo camino para que no le llegue play dos
     * veces (varias apps de fábrica lo toman como play/pausa): el botón de play a su receptor o, si
     * no tiene, a su sesión aunque esté vacía; y su servicio de música (varias apps de Bluetooth
     * tienen servicio pero no hacen caso por ahí). Sin nada de eso, el botón del sistema (nunca en
     * la radio: llevaría a Spotify). Las de fábrica no dicen si suenan: se da por hecho que sí
     * ([Lens.assume]). Solo se abre la app si no hubo a quién mandarle el botón.
     */
    private fun wake(pkg: String, lens: Lens) {
        lens.starting = appLabel(pkg)
        val radioLens = lens === radio
        val button = buttonPlay(pkg)
        val session = !button && sessions().firstOrNull { it.packageName == pkg }?.also { runCatching { it.transportControls.play() } } != null
        val service = connectAndPlay(pkg, fallback = !radioLens && !button && !session)
        trace("despertar $pkg: servicio=${yes(service)} receptor=${yes(button)} sesión=${yes(session)}")
        val none = !service && !button && !session
        if (none && radioLens) return factoryFor(lens)?.let { playFactory(it, pkg, lens) } ?: openAndReturn(pkg, lens)
        if (none) systemPlay()
        if ((button || session) && (sourceOf(pkg) != null || !service)) lens.assume(pkg, true)
        // Las apps de música normales las abre [watchPlay] (y regresa); aquí solo las de la pantalla.
        val opens = none && sourceOf(pkg) != null
        // Uno solo por pieza: el de un despertar anterior no debe quitar el "Abriendo…" de este.
        mainHandler.removeCallbacks(lens.wakeTimeout)
        lens.wakeTimeout = Runnable {
            if (lens.song == null && lens.starting != null && opens) open(pkg)
            lens.starting = null
        }
        mainHandler.postDelayed(lens.wakeTimeout, WAKE_TIMEOUT_MS)
    }

    /**
     * Radios de fábrica que no dejan nada por fuera (p. ej. las TopWay, `com.tw.radio`): solo suenan
     * al abrirlas. Se abre la radio y, si quedó encima de LiteDash, se regresa al inicio en
     * [RADIO_RETURN_MS] (Android deja a la app volver al frente unos segundos después de abrir otra).
     * Lo que pasa con el audio queda en el registro, para los reportes.
     */
    private fun openAndReturn(pkg: String, lens: Lens) {
        lens.starting = null
        val over = open(pkg)
        trace("radio sin control: se abre $pkg${if (over) " y se regresa" else ""}")
        if (!over) return
        // Un solo regreso pendiente aunque se toque play dos veces.
        mainHandler.removeCallbacks(returnFromRadio)
        mainHandler.postDelayed(returnFromRadio, RADIO_RETURN_MS)
    }

    /**
     * FM de fábrica: play sin abrir su app (se le pide el audio a la radio). Si en [FACTORY_ANSWER_MS]
     * la radio no dice que tiene el audio (ni Android lo tiene: la NWD suena por Android), se abre la
     * radio como antes ([openAndReturn]).
     */
    private fun playFactory(f: FactoryRadio, pkg: String, lens: Lens) {
        val asked = SystemClock.elapsedRealtime()
        trace("${f.tag} play sin abrir")
        f.takeAudio()
        mainHandler.removeCallbacks(factoryConfirm)
        factoryConfirm = Runnable {
            lens.starting = null
            // Se cerró (cambiaron de radio o LiteDash se fue): ya no toca.
            if (factory !== f || !f.open) return@Runnable
            // La NWD sin la clave de la fuente del MCU (on == null) solo se puede confirmar por el audio de Android.
            if (f.audioAt >= asked || (f is NwdRadio && f.on == null && musicActive() && !othersPlaying(pkg))) {
                f.started()
                return@Runnable trace("${f.tag} suena sin abrir")
            }
            trace("${f.tag} no confirmó: se abre $pkg")
            openAndReturn(pkg, lens)
        }
        mainHandler.postDelayed(factoryConfirm, FACTORY_ANSWER_MS)
    }

    /** La confirmación pendiente del play de la radio de fábrica; se cancela al pausar o cambiar de radio. */
    private var factoryConfirm = Runnable {}

    private val radioCheck = Runnable {
        val active = musicActive()
        trace("radio tras regresar: audio=${yes(active)}")
        // Sin perfil de su marca nadie dice que la radio suena: si Android tiene audio tras abrirla, se
        // supone que es ella (el botón pasa a pausa y la pieza muestra la radio en vez de quedarse en nada).
        val pkg = sourceApps[SourceKind.RADIO]
        if (active && factoryFor(radio) == null && pkg != null && !radioInternet && !othersPlaying(pkg)) radio.assume(pkg, true)
    }
    private val returnFromRadio = Runnable {
        trace("regreso a LiteDash: audio=${yes(musicActive())}")
        returnHome?.invoke()
        factoryFor(radio)?.started()
        mainHandler.postDelayed(radioCheck, RADIO_RETURN_MS)
    }

    /**
     * LiteDash volvió al frente por su cuenta (el usuario regresó o abrió otra app desde el inicio):
     * ya no se regresa solo. No va en [stop]: al abrir la radio, LiteDash se detiene y eso lo cancelaría.
     */
    fun cancelReturn() {
        mainHandler.removeCallbacks(returnFromRadio)
        mainHandler.removeCallbacks(returnAndPlay)
    }

    private fun musicActive() = context.getSystemService(AudioManager::class.java).isMusicActive

    /**
     * ¿Suena otra cosa que no sea [pkg]? (una sesión ocupada o el reproductor propio). isMusicActive es
     * de todo Android: con esto se sabe si el audio puede ser de la radio o es de otra app.
     */
    private fun othersPlaying(pkg: String?): Boolean = sessions().any { it.packageName != pkg && it.busy() }

    /**
     * FYT (pantallas cuyo MCU elige la fuente de audio): dar play al celular por AVRCP no basta,
     * el MCU tiene que pasar la fuente a Bluetooth. Si al poco rato el celular dice que suena pero
     * Android no tiene audio, se alterna la fuente con su servicio y se vuelve a dar play. No se
     * puede verificar sin la pantalla: todo queda en el registro para los reportes.
     */
    private fun ensureBluetoothSound() {
        if (!isFyt()) return
        mainHandler.postDelayed({
            if (users == 0 || bluetoothDevice == null) return@postDelayed
            val c = avrcpController ?: return@postDelayed
            if (c.playbackState?.state != PlaybackState.STATE_PLAYING || musicActive()) return@postDelayed
            trace("fyt: el celular suena por AVRCP pero no hay audio; se alterna la fuente")
            runCatching {
                context.startService(Intent("com.syu.bt.byav.widgetPlayPause").setClassName("com.syu.bt", "com.syu.bt.broadcast.BtavPlayPauseService"))
            }
            // El servicio alterna la fuente; el play va poco después (no dos veces en <1.5 s).
            mainHandler.postDelayed({ runCatching { c.transportControls.play() } }, 700)
        }, PLAY_CHECK_MS)
    }

    /** Es una pantalla FYT (tiene la app de Bluetooth de su MCU). */
    private fun isFyt() = isInstalled("com.syu.bt")

    private fun isInstalled(pkg: String) = context.hasPackage(pkg)

    /** App del sistema (de la pantalla); si no se puede saber, [unknown]. */
    private fun isSystem(pkg: String, unknown: Boolean = false) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(unknown)

    /** Trae LiteDash al frente si otra app lo tapa (lo pone la app). */
    var returnHome: (() -> Unit)? = null

    /**
     * Una tecla multimedia a la app de [lens], como el volante, por un solo camino: a su receptor o,
     * sin receptor, a su sesión aunque esté vacía. Sin ninguno, al sistema de audio, salvo la radio y
     * el reproductor propio: ahí el sistema la llevaría a otra app (y podría arrancar Spotify).
     * False = no hubo a quién.
     */
    private fun press(lens: Lens, code: Int): Boolean {
        val pkg = target(lens)
        val receiver = keyReceiver(pkg)
        val session = if (receiver != null) null else pkg?.takeUnless(::isOwn)?.let { p -> lens.controller?.takeIf { it.packageName == p } ?: sessions().firstOrNull { it.packageName == p } }
        trace("tecla ${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_MEDIA_")} $pkg: receptor=${yes(receiver != null)} sesión=${yes(session != null)}")
        when {
            receiver != null -> sendMediaKey(context, code, receiver)
            session != null -> for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching { session.dispatchMediaButtonEvent(KeyEvent(action, code)) }
            // Radio sin receptor ni sesión: la pausa va al sistema de audio (como el botón del volante); el play
            // no, porque la radio solo arranca abriéndola.
            // Solo si nada más suena: si no, el sistema le daría la pausa a esa otra app.
            lens === radio && code == KeyEvent.KEYCODE_MEDIA_PAUSE && pkg != null && !isOwn(pkg) && !othersPlaying(pkg) -> sendMediaKey(context, code)
            lens === radio || pkg != null && isOwn(pkg) -> return false
            else -> sendMediaKey(context, code)
        }
        return true
    }

    /**
     * ¿Hay a quién mandarle una tecla de [lens]? La música siempre (al sistema); la radio, a su receptor
     * (si no tiene, su sesión ya recibe lo mismo por sus controles).
     */
    private fun canKey(lens: Lens): Boolean = lens !== radio || keyReceiver(target(lens)) != null

    /**
     * El receptor del botón de play de [pkg] (nunca el de LiteDash: su reproductor tiene sesión).
     * Se pregunta una vez por app (es una llamada al sistema y la pieza lo consulta al pintarse).
     */
    private fun keyReceiver(pkg: String?): ComponentName? =
        pkg?.takeUnless(::isOwn)?.let { p -> receivers.getOrPut(p) { mediaButtonReceivers(p).firstOrNull() } }
    private val receivers = HashMap<String, ComponentName?>()

    /** La pieza Radio sin sesión pero con una FM de la pantalla que atiende teclas: lleva anterior/siguiente. */
    fun radioHasKeys(): Boolean {
        val pkg = radioApp ?: return false
        return !isOwn(pkg) && keyReceiver(pkg) != null
    }

    /** El servicio de música (MediaBrowserService) de [pkg]; null si no ofrece. */
    private fun browserService(pkg: String) = runCatching {
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0).firstOrNull()?.serviceInfo
    }.getOrNull()

    /**
     * Se conecta al servicio de música de [pkg] y le pide play; si no lo acepta y [fallback], el botón
     * del sistema. False = no tiene servicio.
     */
    private fun connectAndPlay(pkg: String, fallback: Boolean): Boolean {
        val service = browserService(pkg) ?: return false
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching { MediaController(context, browser.sessionToken).transportControls.play() }
                browser.disconnect()
            }

            override fun onConnectionFailed() {
                if (fallback) systemPlay()
            }
        }, null)
        // Si el servicio nunca contesta, la conexión no se queda colgada para siempre.
        mainHandler.postDelayed({ runCatching { browser.disconnect() } }, WAKE_TIMEOUT_MS)
        return runCatching { browser.connect() }.isSuccess
    }

    /** El botón de play directo al receptor de [pkg] (las apps de Bluetooth/radio lo atienden así). */
    private fun buttonPlay(pkg: String): Boolean {
        val receiver = keyReceiver(pkg) ?: return false
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, receiver)
        return true
    }

    /** El botón de play como el del volante: Android lo lleva a la última app de música. */
    private fun systemPlay() = sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Receptores del botón de play de [pkg] (o de todas las apps con null). */
    private fun mediaButtonReceivers(pkg: String? = null): List<ComponentName> = runCatching {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { if (pkg != null) setPackage(pkg) }
        context.packageManager.queryBroadcastReceivers(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())

    /**
     * Apps de música instaladas: las que ofrecen su biblioteca a Android
     * (Spotify, Amazon Music, YouTube Music, Deezer, el reproductor de la pantalla…).
     */
    fun musicApps(): Set<String> = runCatching {
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .filter { it != context.packageName && it !in NOT_MUSIC_APPS }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Adivina, una vez, la app de radio de la pantalla: entre las del sistema que atienden el botón de
     * play y se pueden abrir, la que lleva "radio" o "fm" en el paquete o en el nombre. Solo si hay una
     * sola candidata (si hay dudas, mejor nada: se elige en Ajustes → Apps → Radio). El Bluetooth no se
     * adivina: [bluetoothApp] ya usa la app de marca instalada (adivinar por nombre enlazó ZLink en una
     * TopWay). Corre en un hilo aparte (son varias consultas al sistema): los nombres se leen sin la
     * caché del hilo principal, y el resultado se guarda en el principal.
     */
    private fun guessSources() {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val players = runCatching { mediaButtonReceivers().map { it.packageName }.distinct() }.getOrDefault(emptyList())
            .filter { it != context.packageName && it in launchable && isSystem(it) }
        val label = { pkg: String -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("") }
        val radio = players.filter { pkg -> RADIO_WORDS.any { it in tokens(pkg, label(pkg)) } }.singleOrNull() ?: return
        mainHandler.post { if (SourceKind.RADIO !in sourceApps) setSource(SourceKind.RADIO, radio) }
    }

    /** Palabras sueltas del paquete y del nombre, en minúsculas ("com.syu.bt" → com, syu, bt). */
    private fun tokens(pkg: String, label: String): Set<String> =
        (pkg.split('.') + label.split(Regex("[^\\p{L}\\p{N}]+"))).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    // Lo último que pasó con las teclas, los avisos y el despertar (solo en memoria, para los reportes).
    private val traceLog = ArrayDeque<String>()

    private fun trace(event: String) {
        traceLog.addLast("${SystemClock.elapsedRealtime() / 1000 % 100_000}s $event")
        while (traceLog.size > TRACE_SIZE) traceLog.removeFirst()
    }

    /** Para los reportes: qué Bluetooth trae la pantalla y qué apps de marca, para saber su familia. */
    private fun describeBluetooth(): String = buildString {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val service = bluetoothService
        appendLine("Bluetooth de Android: AVRCP=${service?.let { "${it.packageName}/${it.name}" } ?: "no"}; adaptador=${adapter?.let { if (it.isEnabled) "encendido" else "apagado" } ?: "no"}")
        val brand = BRAND_BT_APPS.filter(::isInstalled)
        appendLine("Apps de Bluetooth de marca: ${brand.joinToString().ifBlank { "ninguna" }}")
    }

    /**
     * Lo que LiteDash ve de la música en esta pantalla, para los reportes: las fuentes enlazadas
     * (con su receptor, servicio y aviso), cada sesión con su estado, acciones y si trae canción,
     * qué suponen las piezas, si Android tiene audio sonando y lo último que pasó. Sin nombres
     * de canciones ni del celular.
     */
    fun describe(): String = buildString {
        // Solo apps del sistema y las que el usuario enlazó o eligió: no la lista de todo lo instalado.
        val buttons = mediaButtonReceivers().map { it.packageName }.distinct().filter(::relevant)
        val browsers = musicApps().filter(::relevant)
        appendLine("Música elegida: ${preferred ?: "ninguna"}")
        appendLine("Fuentes: radio=${radioApp ?: "-"}, bluetooth=${bluetoothApp() ?: "-"}")
        val notes = sourceApps.values.toSet().let { apps -> NotificationService.active().filter { it.packageName in apps } }
        for ((kind, pkg) in sourceApps) {
            val own = notes.filter { it.packageName == pkg }
            appendLine(
                "  ${kind.name.lowercase()} $pkg: receptor=${yes(keyReceiver(pkg) != null)} servicio=${yes(browserService(pkg) != null)} " +
                    "avisos=${own.size} música=${yes(own.any { it.isMedia() })}",
            )
        }
        appendLine("Bluetooth: ${if (bluetoothDevice != null) "celular conectado" else "-"}; USB: ${if (usbDrive != null) "memoria conectada" else "-"}")
        append(describeBluetooth())
        appendLine("Apps de música: ${browsers.joinToString().ifBlank { "ninguna" }}")
        appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
        if (!hasPermission()) appendLine("Sesiones: sin acceso a notificaciones")
        else {
            val list = sessions()
            appendLine("Sesiones: ${if (list.isEmpty()) "ninguna" else ""}")
            for (c in list) {
                val m = c.metadata
                appendLine(
                    "  ${c.packageName} estado=${c.playbackState?.state ?: "-"} acciones=${actionNames(c.playbackState?.actions ?: 0L)} " +
                        "título=${yes(m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty())} " +
                        "artista=${yes(m.text(MediaMetadata.METADATA_KEY_ARTIST).isNotEmpty())}",
                )
            }
        }
        for ((name, lens) in listOf("Pieza música" to music, "Pieza radio" to radio)) {
            val s = lens.song
            appendLine(
                "$name: ${s?.let { "${it.pkg.ifEmpty { "?" }} ${if (it.playing) "suena" else "en pausa"}${if (it.blind) " (supuesto)" else ""}" } ?: "nada"}" +
                    (lens.assumedPkg?.let { "; supone $it" } ?: ""),
            )
        }
        appendLine("Audio sonando en Android: ${yes(context.getSystemService(AudioManager::class.java).isMusicActive)}")
        if (radioApp?.startsWith(NwdRadio.PACKAGE) == true) appendLine(NwdRadio.describe(context, factory as? NwdRadio))
        if (TopWayRadio.available) {
            val tw = factory as? TopWayRadio
            appendLine(
                "TopWay: abierto=${yes(tw?.open == true)} estación=${tw?.station ?: "-"} " +
                    "nombre=${tw?.name?.ifEmpty { "-" } ?: "-"} audio=${tw?.on?.let(::yes) ?: "?"} " +
                    "servicioXT=${yes(xtService())} se cerró=${yes(store.getBoolean(KEY_TOPWAY_OPENING, false))}",
            )
        }
        append("Últimos eventos:")
        if (traceLog.isEmpty()) append(" ninguno") else traceLog.forEach { append("\n  ").append(it) }
    }

    /** Fuentes enlazadas sin receptor ni servicio: no hay cómo hablarles (el reporte las describe con [AppProbe]). */
    fun silentSources(): List<String> = sourceApps.values.distinct().filter { !isOwn(it) && isSilent(it) }

    /** El servicio de comandos de TopWay (otra vía para la radio, aún sin usar): para los reportes. */
    private fun xtService() = runCatching {
        context.packageManager.queryIntentServices(Intent(TOPWAY_XT_BIND), 0).isNotEmpty()
    }.getOrDefault(false)

    /** ¿[pkg] va en el reporte? Las del sistema, las enlazadas y la elegida. */
    private fun relevant(pkg: String): Boolean =
        pkg == preferred || pkg in sourceApps.values || isSystem(pkg)

    private fun actionNames(actions: Long): String = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
        PlaybackState.ACTION_SKIP_TO_NEXT to "next", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "prev", PlaybackState.ACTION_STOP to "stop",
        PlaybackState.ACTION_SEEK_TO to "seek",
    ).filter { actions and it.first != 0L }.joinToString(",", "[", "]") { it.second }

    /** Abre la app que está sonando o, si no hay, la tocada o la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: song?.pkg?.ifEmpty { null } ?: modeApp ?: return)
    }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    /** True = se abrió encima de LiteDash (no a un lado en pantalla dividida). */
    private fun open(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        if (opener?.invoke(context, intent, pkg) == true) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/** Sonando o cargando. */
private fun MediaController.busy(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
    else -> false
}

/** Los extras de un aviso como mapa (un extra que no se puede leer deja el mapa vacío). */
@Suppress("DEPRECATION") // Bundle.get: los avisos traen tipos variados
private fun Bundle?.toMap(): Map<String, Any?> {
    val b = this ?: return emptyMap()
    return runCatching { b.keySet().associateWith { b.get(it) } }.getOrDefault(emptyMap())
}

/** El primer texto no vacío de [keys] (algunas apps mandan el título vacío y el bueno en otro campo). */
private fun MediaMetadata?.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { this?.getString(it)?.takeIf(String::isNotBlank) }.orEmpty()

/** La sesión que comparte un aviso de música; null si no trae. */
private fun StatusBarNotification.sessionToken(): MediaSession.Token? =
    BundleCompat.getParcelable(notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)

/** Aviso de música: con su sesión o con el estilo multimedia ("android.template"; la constante es de la API 24). */
private fun StatusBarNotification.isMedia(): Boolean {
    val extras = notification.extras
    return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getString("android.template").orEmpty().endsWith("MediaStyle")
}
