package com.irvingguerra.litedash.feature.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Lo que la pantalla dice de sí misma en el QR y en su `info`. */
class ScreenInfo(val app: String, val car: String, val carId: String, val screen: String)

/** Lo que se dibuja para vincular: el texto del QR y el código de 4 dígitos de esa llave. */
class Pairing(val text: String, val code: String)

/** Lo que suena en el celular (mensaje `music` con su `art`, PROTOCOLO_LINK.md §5b). */
class PhoneSong(
    val title: String,
    val artist: String,
    /** La app que suena en el celular ("Spotify"). */
    val app: String,
    val playing: Boolean,
    val buffering: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** Cuándo llegó (reloj de arranque), para avanzar la barra sin preguntar. */
    val readAt: Long,
    val speed: Float,
    val seekable: Boolean,
    /** El celular tiene carátula (llega aparte); sin ella, la pantalla puede buscarla. */
    val hasArt: Boolean,
    val art: Bitmap?,
)

/**
 * El enlace con el celular (LiteDash Connect), lado pantalla de Link v1 (PROTOCOLO_LINK.md): el
 * celular es el servidor y la pantalla lo busca en la puerta de enlace de la red, en la última
 * dirección que funcionó y por NSD.
 *
 * Ya vinculada intenta al abrir LiteDash ([start]), al cambiar la red y cuando NSD encuentra al
 * celular; si la conexión se cae, reintenta cada vez más espaciado (5 s… hasta 1 min): Android puede
 * cerrar y volver a abrir LiteDash Connect sin que cambie la red. Con su panel abierto ([watch])
 * intenta cada 2 s. Apagado ([stop]) no deja hilos, receptores ni sockets.
 *
 * El estado se maneja en el hilo principal; la red, en un hilo por intento.
 */
class PhoneLink(context: Context, private val screen: () -> ScreenInfo) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "phone-link-cmd") }

    /** Hay un celular vinculado (su llave guardada). */
    var paired by mutableStateOf(store.contains(KEY))
        private set

    /** Hay conexión con el celular ahora. */
    var connected by mutableStateOf(false)
        private set

    /** Modelo del celular vinculado y versión de su app (de su último `info`). */
    var phoneName by mutableStateOf(store.getString(PHONE_NAME, null))
        private set
    var phoneApp by mutableStateOf(store.getString(PHONE_APP, null))
        private set

    /** El celular dijo que no puede leer su música (falta "Permitir la música" en LiteDash Connect). */
    var musicBlocked by mutableStateOf(false)
        private set

    /** Lo que suena en el celular; null = nada, o sin conexión. Avisa a [onMusic] en el hilo principal. */
    var song: PhoneSong? = null
        private set
    var onMusic: ((PhoneSong?) -> Unit)? = null

    /** Play, pausa, siguiente, anterior o salto ([ms]) a la música del celular. Falso si no se puede. */
    fun command(action: String, ms: Long = 0): Boolean {
        val open = session?.takeIf { it.music } ?: return false
        // En un solo hilo: play y pausa seguidos llegan en el orden en que se tocaron.
        commands.execute { runCatching { open.send(JSONObject().put("t", "cmd").put("do", action).put("ms", ms)) } }
        return true
    }

    /** El QR para vincular; solo con el panel abierto y sin celular vinculado. */
    var pairing by mutableStateOf<Pairing?>(null)
        private set

    private var running = false
    private var watching = false

    /** La llave del QR: provisional hasta que un saludo con ella termine bien. */
    private var offered: ByteArray? = null
    private var offeredAt = 0L

    private var attempt: Attempt? = null
    private var session: Session? = null
    private var networkWatched = false
    private var discovery: Discovery? = null
    /** Dónde anunció NSD al celular ("host:puerto"). */
    private var announced: String? = null

    // Para el reporte.
    private var lastError: String? = null
    @Volatile private var lastGateways = ""

    private val active get() = running && (paired || watching)

    /** LiteDash volvió al frente: si hay celular vinculado, lo busca. */
    fun start() {
        running = true
        sync()
        connect()
    }

    /** Ajustes → Cuenta se abrió o se cerró: abierta, intenta cada 2 s y mantiene el QR al día. */
    fun watch(on: Boolean) {
        watching = on && running
        main.removeCallbacks(tick)
        if (watching) tick.run() else sync()
    }

    /** Olvida al celular (le avisa si hay conexión) y deja listo un QR con otra llave. */
    fun unlink() {
        drop(bye = true)
        forget()
    }

    /** Para "Enviar un reporte". */
    fun describe(): String = "Celular: vinculado=${yes(paired)}, conectado=${yes(connected)}, " +
        "puerta de enlace=${lastGateways.ifEmpty { "—" }}, última dirección=${store.getString(LAST_ADDRESS, null) ?: "—"}, " +
        "NSD=${if (discovery != null) "buscando" else "no"}${announced?.let { " ($it)" } ?: ""}, error=${lastError ?: "—"}, " +
        "música=${when { session?.music != true -> "no" ; musicBlocked -> "sin permiso"; song == null -> "nada"; song?.playing == true -> "suena"; else -> "en pausa" }}"

    private fun yes(value: Boolean) = if (value) "sí" else "no"

    private val tick = object : Runnable {
        override fun run() {
            refreshPairing()
            sync()
            connect()
            main.postDelayed(this, WATCH_RETRY_MS)
        }
    }

    private val retry = Runnable { connect() }
    /** Reintentos seguidos desde que se cayó la conexión (0 = conectado o nunca se cayó). */
    private var retries = 0

    /** Con el panel abierto y sin celular: una llave nueva si no hay o si la del QR ya venció. */
    private fun refreshPairing() {
        if (paired) {
            pairing = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (offered != null && now - offeredAt < OFFER_MS) return
        val key = ByteArray(LinkProtocol.KEY_BYTES).also(random::nextBytes)
        offered = key
        offeredAt = now
        val info = screen()
        pairing = Pairing(LinkProtocol.qrText(screenId(), key, info.car, info.screen), LinkProtocol.code(key))
    }

    /** La identidad de esta pantalla: 8 bytes al azar, fijos desde la primera vez. */
    private fun screenId(): String = store.getString(SCREEN_ID, null)
        ?: ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { store.edit().putString(SCREEN_ID, it).apply() }

    /** Escucha la red y a NSD solo mientras hagan falta. */
    private fun sync() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (active != networkWatched && manager != null) runCatching {
            if (active) manager.registerNetworkCallback(NetworkRequest.Builder().build(), onNetwork)
            else manager.unregisterNetworkCallback(onNetwork)
            networkWatched = active
        }
        // NSD se prende después de un intento fallido ([closed]); aquí solo se apaga.
        if (!active || connected) discover(false)
    }

    private val onNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { connect() }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            main.post { connect() }
        }
    }

    private fun discover(on: Boolean) {
        if (on == (discovery != null)) return
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        val current = discovery
        if (current != null) {
            runCatching { manager.stopServiceDiscovery(current) }
            discovery = null
        } else {
            val listener = Discovery(manager)
            runCatching {
                manager.discoverServices(LinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                discovery = listener
            }
        }
    }

    private inner class Discovery(private val manager: NsdManager) : NsdManager.DiscoveryListener {
        override fun onServiceFound(service: NsdServiceInfo) {
            @Suppress("DEPRECATION") // el reemplazo (registerServiceInfoCallback) es de Android 14
            runCatching { manager.resolveService(service, Resolved()) }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            main.post { if (discovery === this) discovery = null }
        }

        override fun onServiceLost(service: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        private inner class Resolved : NsdManager.ResolveListener {
            override fun onServiceResolved(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = service.host?.hostAddress ?: return
                main.post {
                    if (discovery !== this@Discovery) return@post
                    announced = "$host:${service.port}"
                    connect()
                }
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = Unit
        }
    }

    /** Un intento de conexión, si toca y no hay otro en curso. */
    private fun connect() {
        if (!active || attempt != null) return
        val stored = store.getString(KEY, null)
        val key = if (stored != null) runCatching { Base64.decode(stored) }.getOrNull() else offered.takeIf { watching }
        if (key == null || key.size != LinkProtocol.KEY_BYTES) return
        main.removeCallbacks(retry)
        val next = Attempt(key, fresh = stored == null, screenId(), screen(), listOfNotNull(store.getString(LAST_ADDRESS, null), announced))
        attempt = next
        Thread(next, "phone-link").start()
    }

    /** Suelta la conexión o el intento en curso; con [bye], antes le avisa al celular que se desvincula. */
    private fun drop(bye: Boolean = false) {
        val current = attempt ?: return
        val open = session
        // El hilo del intento ya no avisará al cerrar (se le soltó): la música del celular se quita aquí.
        played(current, null, blocked = false)
        attempt = null
        session = null
        connected = false
        current.cancelled = true
        io {
            if (bye && open != null) runCatching { open.send(JSONObject().put("t", "bye").put("reason", "unlink")) }
            current.close()
        }
    }

    /** Borra la llave y lo que se sabía del celular; con el panel abierto sale un QR nuevo. */
    private fun forget() {
        store.edit().remove(KEY).remove(PHONE_NAME).remove(PHONE_APP).apply()
        paired = false
        retries = 0
        main.removeCallbacks(retry)
        phoneName = null
        phoneApp = null
        offered = null
        if (watching) refreshPairing()
        sync()
    }

    // Lo que sigue llega del hilo del intento, ya en el hilo principal.

    private fun opened(from: Attempt, open: Session, address: String) {
        if (attempt !== from) return
        val edit = store.edit().putString(LAST_ADDRESS, address)
        // El saludo con la llave del QR terminó bien: desde ahora es la definitiva.
        if (from.fresh) edit.putString(KEY, Base64.encode(from.key))
        edit.apply()
        if (from.fresh) {
            paired = true
            offered = null
            pairing = null
        }
        session = open
        connected = true
        retries = 0
        lastError = null
        sync()
    }

    private fun heard(from: Attempt, name: String, version: String, music: Boolean) {
        if (attempt !== from) return
        session?.music = music
        phoneName = name
        phoneApp = version
        store.edit().putString(PHONE_NAME, name).putString(PHONE_APP, version).apply()
    }

    /** Llegó la música del celular (ya en el hilo principal). */
    private fun played(from: Attempt, next: PhoneSong?, blocked: Boolean) {
        if (attempt !== from) return
        musicBlocked = blocked
        song = next
        onMusic?.invoke(next)
    }

    private fun closed(from: Attempt, error: String?, bye: Boolean) {
        if (attempt !== from) return
        played(from, null, blocked = false)
        val wasConnected = connected
        attempt = null
        session = null
        connected = false
        if (error != null) lastError = error
        if (bye) return forget()
        sync()
        if (!active) return
        // Se cayó (o sigue sin volver tras caerse): otro intento, cada vez más espaciado. Nunca conectó en
        // esta red: queda esperando a la red o a NSD.
        if (wasConnected || retries > 0) {
            main.postDelayed(retry, RETRY_MS.getOrElse(retries) { RETRY_MS.last() })
            retries++
        }
        if (!wasConnected) discover(true)
    }

    /** Una conexión ya saludada: escribe de un mensaje a la vez (el contador del cifrado va en orden). */
    private class Session(private val socket: Socket, private val channel: SecureChannel) {
        private val output = socket.getOutputStream()
        @Volatile var lastSent = SystemClock.elapsedRealtime()
        /** El celular declaró la música en su `info`. */
        @Volatile var music = false

        fun send(message: JSONObject) = synchronized(this) {
            output.write((channel.seal(message.toString()) + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            lastSent = SystemClock.elapsedRealtime()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    /** Un intento en su hilo: busca al celular, saluda y, si conecta, atiende hasta que se cierre. */
    private inner class Attempt(
        val key: ByteArray,
        /** La llave es la del QR (todavía no guardada). */
        val fresh: Boolean,
        private val id: String,
        private val info: ScreenInfo,
        private val known: List<String>,
    ) : Runnable {
        @Volatile var cancelled = false
        @Volatile private var socket: Socket? = null
        private val music = MusicReader()

        fun close() {
            runCatching { socket?.close() }
        }

        override fun run() {
            var error: String? = "sin celular en la red"
            var bye = false
            var served = false
            for (address in (gateways() + known).distinct()) {
                if (cancelled) break
                val host = address.substringBeforeLast(':')
                val port = address.substringAfterLast(':').toIntOrNull() ?: continue
                val open = Socket()
                socket = open
                try {
                    // Cancelado justo antes de guardar el socket: nadie más lo cerraría.
                    if (cancelled) break
                    open.connect(InetSocketAddress(host, port), CONNECT_MS)
                    val reader = LineReader(BufferedInputStream(open.getInputStream()))
                    val channel = greet(open, reader)
                    val ready = Session(open, channel)
                    ready.send(
                        JSONObject().put("t", "info").put("role", "screen").put("app", info.app).put("car", info.car)
                            .put("carId", info.carId).put("screen", info.screen).put("caps", JSONArray().put(CAP_MUSIC)),
                    )
                    served = true
                    main.post { opened(this, ready, address) }
                    bye = serve(open, reader, channel, ready)
                    error = null
                } catch (e: Exception) {
                    // Nadie escucha en esa dirección: no es un error que contar, se prueba la siguiente.
                    if (open.isConnected) error = "$address: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    runCatching { open.close() }
                }
                // Ya hubo conexión (y se cerró o se cayó): este intento termina aquí.
                if (served) break
            }
            main.post { closed(this, error.takeIf { !cancelled }, bye) }
        }

        /** Las puertas de enlace (IPv4) de la red activa: en el hotspot del celular, es el celular. */
        private fun gateways(): List<String> {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val routes = runCatching { manager?.getLinkProperties(manager.activeNetwork)?.routes }.getOrNull().orEmpty()
            val found = routes.mapNotNull { route ->
                route.gateway?.takeIf { route.isDefaultRoute && it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress
            }.distinct()
            lastGateways = found.joinToString()
            return found.map { "$it:${LinkProtocol.PORT}" }
        }

        /** El saludo: cada lado prueba que tiene la llave. Regresa el canal cifrado o lanza. */
        private fun greet(open: Socket, reader: LineReader): SecureChannel {
            open.soTimeout = HELLO_MS
            val deadline = SystemClock.elapsedRealtime() + HELLO_MS
            val nc = ByteArray(LinkProtocol.NONCE_BYTES).also(random::nextBytes)
            val hello = JSONObject().put("t", "hello").put("v", LinkProtocol.VERSION).put("id", id).put("nc", Base64.encode(nc))
            open.getOutputStream().run {
                write((hello.toString() + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            val answer = JSONObject(reader.readLine(LinkProtocol.HELLO_LINE_MAX) ?: throw IOException("el celular no conoce esta pantalla"))
            if (answer.optString("t") == "error") throw IOException("el celular contestó: ${answer.optString("code")}")
            if (answer.optString("t") != "hello" || answer.optInt("v") != LinkProtocol.VERSION) throw IOException("saludo desconocido")
            val ns = Base64.decode(answer.getString("ns"))
            if (ns.size != LinkProtocol.NONCE_BYTES) throw IOException("saludo desconocido")
            if (!LinkProtocol.sameMac(LinkProtocol.phoneMac(key, nc, ns), Base64.decode(answer.getString("mac")))) throw IOException("la llave no coincide")
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("saludo lento")
            val auth = JSONObject().put("t", "auth").put("mac", Base64.encode(LinkProtocol.screenMac(key, nc, ns)))
            open.getOutputStream().run {
                write((auth.toString() + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            return SecureChannel(LinkProtocol.screenToPhoneKey(key, nc, ns), LinkProtocol.phoneToScreenKey(key, nc, ns))
        }

        /** Atiende la conexión hasta que se cierre; true si el celular se despidió (`bye`). Lanza si se cae. */
        private fun serve(open: Socket, reader: LineReader, channel: SecureChannel, ready: Session): Boolean {
            var lastHeard = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHeard >= SILENCE_MS) throw IOException("sin respuesta del celular")
                val quiet = now - maxOf(lastHeard, ready.lastSent)
                if (quiet >= PING_MS) {
                    ready.send(JSONObject().put("t", "ping"))
                    continue
                }
                // Despierta solo cuando toca mandar un ping o dar la conexión por caída.
                open.soTimeout = minOf(PING_MS - quiet, SILENCE_MS - (now - lastHeard)).toInt().coerceAtLeast(1)
                val line = try {
                    reader.readLine(LinkProtocol.LINE_MAX) ?: throw IOException("el celular cerró")
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val message = JSONObject(channel.open(line))
                lastHeard = SystemClock.elapsedRealtime()
                when (message.optString("t")) {
                    "info" -> {
                        val name = message.optString("name")
                        val version = message.optString("app")
                        val caps = message.optJSONArray("caps")
                        val music = caps != null && (0 until caps.length()).any { caps.optString(it) == CAP_MUSIC }
                        // Ya se puede mandar `cmd` (antes de que el hilo principal se entere).
                        ready.music = music
                        main.post { heard(this, name, version, music) }
                    }
                    "music" -> {
                        val next = music.heard(message)
                        val blocked = !message.optBoolean("access", true)
                        main.post { played(this, next, blocked) }
                    }
                    "art" -> music.art(message)?.let { next -> main.post { played(this, next, blocked = false) } }
                    "ping" -> ready.send(JSONObject().put("t", "pong"))
                    "bye" -> return true
                    // "pong" y lo que no se conoce: nada (compatibilidad hacia adelante).
                }
            }
        }
    }

    /**
     * Arma la canción con los mensajes `music` y `art` (en el hilo de la conexión): la carátula se guarda
     * por canción (`track`) y se pega a cada `music` de esa misma canción.
     */
    private class MusicReader {
        private var last: PhoneSong? = null
        private var track: String? = null
        private var artTrack: String? = null
        private var art: Bitmap? = null

        /** null = no suena nada (o el celular no tiene permiso). */
        fun heard(m: JSONObject): PhoneSong? {
            val title = m.optString("title")
            if (title.isEmpty()) {
                last = null
                track = null
                return null
            }
            track = m.optString("track")
            val hasArt = m.optBoolean("art")
            return PhoneSong(
                title = title,
                artist = m.optString("artist"),
                app = m.optString("app"),
                playing = m.optBoolean("playing"),
                buffering = m.optBoolean("buffering"),
                durationMs = m.optLong("duration").coerceAtLeast(0),
                positionMs = m.optLong("position").coerceAtLeast(0),
                readAt = SystemClock.elapsedRealtime(),
                speed = m.optDouble("speed", 1.0).toFloat().takeIf { it > 0 } ?: 1f,
                seekable = m.optBoolean("seek"),
                hasArt = hasArt,
                art = art.takeIf { hasArt && artTrack == track },
            ).also { last = it }
        }

        /**
         * La carátula de una canción; regresa la canción actual con ella si es la suya. Vacía o dañada (el
         * celular no pudo comprimirla): la canción queda sin carátula y la pantalla la busca por su cuenta.
         */
        fun art(m: JSONObject): PhoneSong? {
            val bytes = runCatching { Base64.decode(m.optString("jpg")) }.getOrNull()
            val decoded = bytes?.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            artTrack = m.optString("track")
            art = decoded
            val current = last?.takeIf { artTrack == track && it.hasArt } ?: return null
            return PhoneSong(
                current.title, current.artist, current.app, current.playing, current.buffering, current.durationMs,
                current.positionMs, current.readAt, current.speed, current.seekable, hasArt = decoded != null, decoded,
            ).also { last = it }
        }
    }

    private fun io(block: () -> Unit) = Thread(block, "phone-link-io").start()

    private companion object {
        const val PREFS = "link"
        const val SCREEN_ID = "screen_id"
        const val KEY = "key"
        const val LAST_ADDRESS = "last_address"
        const val PHONE_NAME = "phone_name"
        const val PHONE_APP = "phone_app"
        const val CAP_MUSIC = "music"

        /** Lo que vive la llave de un QR sin vincular. */
        const val OFFER_MS = 5 * 60_000L
        const val WATCH_RETRY_MS = 2_000L
        val RETRY_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)
        const val CONNECT_MS = 1_500
        const val HELLO_MS = 5_000
        const val PING_MS = 20_000L
        const val SILENCE_MS = 45_000L
    }
}
