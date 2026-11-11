package com.irvingguerra.litedash

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.PI
import kotlin.math.atan2
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.concurrent.thread

/** Avisos del sistema para LiteDash: escucha solo mientras se ve (con otra app al frente, nada). */
private fun Context.listen(receiver: BroadcastReceiver, action: String) =
    ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

/**
 * Volumen de la música en la barra como una rueda táctil, igual a la del aire acondicionado de un auto: se
 * desliza el dedo sobre ella. En la barra lateral va en vertical (arriba sube, abajo baja) y en la de abajo en
 * horizontal (a la derecha sube, a la izquierda baja). Una deslizada completa a lo largo de la rueda mueve
 * [VOLUME_POINTS_PER_SWIPE] puntos, y dentro de ella el volumen cambia punto por punto según lo que se
 * recorra (una deslizada corta mueve pocos puntos). Con el volumen del chip de la pantalla (fijo para
 * Android) manda las teclas de volumen, como el botón físico.
 */
@Composable
fun Volume(buttonSize: Dp, vertical: Boolean = false) {
    val c = LocalColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    // Cuánto ha girado la rueda (en radianes): solo mueve las rayas, para que se vea rodar.
    var roll by remember { mutableFloatStateOf(0f) }
    LifecycleStartEffect(audio) {
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        context.listen(receiver, VOLUME_CHANGED)
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    // Un punto de volumen hacia arriba (+1) o hacia abajo (-1). Falso si ya está en el tope.
    fun stepBy(direction: Int): Boolean {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (before + direction).coerceIn(0, max)
        if (target == before) return false
        if (!audio.isVolumeFixed) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Si Android no lo dejó cambiar (la pantalla maneja su propio chip), la tecla de volumen.
        if (audio.isVolumeFixed || level == before) volumeKey(if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        return true
    }
    val fixed = audio.isVolumeFixed
    val wheelWidth = if (vertical) buttonSize * 1.2f else buttonSize * 2.4f
    val wheelHeight = if (vertical) buttonSize * 2.3f else buttonSize * 0.8f
    val shape = RoundedCornerShape(14.dp)
    val wheel = @Composable {
        Box(
            Modifier.size(wheelWidth, wheelHeight).clip(shape).background(Color(0xFF1A1D22)).border(1.dp, c.line, shape).padding(4.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .pointerInput(vertical) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val length = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
                            val radius = length / 2f
                            val slop = 8.dp.toPx()
                            val start = if (vertical) down.position.y else down.position.x
                            var last = start
                            var decided = false
                            var ignore = false
                            var owed = 0f
                            while (true) {
                                val move = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!move.pressed) break
                                val now = if (vertical) move.position.y else move.position.x
                                if (!decided) {
                                    if (abs(now - start) < slop) continue
                                    decided = true
                                    // Si se dejó presionada antes de mover (acomodar la barra), no es del volumen.
                                    ignore = move.uptimeMillis - down.uptimeMillis > 400L
                                    last = now
                                    continue
                                }
                                if (ignore) continue
                                // Hacia arriba (vertical) o hacia la derecha (horizontal) sube el volumen.
                                val delta = if (vertical) last - now else now - last
                                last = now
                                roll += delta / radius
                                owed += delta / length * VOLUME_POINTS_PER_SWIPE
                                while (owed >= 1f) owed = if (stepBy(1)) owed - 1f else 0f
                                while (owed <= -1f) owed = if (stepBy(-1)) owed + 1f else 0f
                            }
                        }
                    },
            ) {
                val len = if (vertical) size.height else size.width
                val thick = if (vertical) size.width else size.height
                val r = len / 2f - 3.dp.toPx()
                val mid = len / 2f
                drawRect(Color(0xFF22242A))
                // Las rayas de la rueda: más juntas hacia los bordes, como un cilindro visto de frente.
                val pitch = 8.5f * PI.toFloat() / 180f
                val base = roll % pitch
                for (k in -14..14) {
                    val th = k * pitch + base
                    if (abs(th) >= 1.47f) continue
                    val b = cos(th)
                    val pos = if (vertical) mid - r * sin(th) else mid + r * sin(th)
                    val t = (5.2.dp.toPx() * b).coerceAtLeast(1.2.dp.toPx())
                    val shade = (40f + 150f * b.pow(1.6f)) / 255f
                    val ridge = Color(shade, shade, (shade * 1.03f).coerceAtMost(1f))
                    if (vertical) drawRect(ridge, Offset(0f, pos - t / 2), Size(thick, t))
                    else drawRect(ridge, Offset(pos - t / 2, 0f), Size(t, thick))
                }
                // Sombra en los extremos: da el volumen de cilindro.
                val dark = Color.Black.copy(alpha = 0.85f)
                val fade = arrayOf(0f to dark, 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to dark)
                drawRect(brush = if (vertical) Brush.verticalGradient(*fade) else Brush.horizontalGradient(*fade))
                // La marca blanca (como la de la rueda del aire): dónde va el volumen.
                val frac = (level.toFloat() / max).coerceIn(0f, 1f)
                val th = (-62f + 124f * frac) * PI.toFloat() / 180f
                val at = if (vertical) mid - r * sin(th) else mid + r * sin(th)
                val w = 2.5.dp.toPx() * cos(th)
                if (vertical) drawLine(Color.White, Offset(thick * 0.15f, at), Offset(thick * 0.85f, at), w, StrokeCap.Round)
                else drawLine(Color.White, Offset(at, thick * 0.15f), Offset(at, thick * 0.85f), w, StrokeCap.Round)
            }
        }
    }
    val number = @Composable { m: Modifier ->
        if (fixed) Icon(Icons.volume, c.text, 20.dp)
        else Label(level.toString(), Typography.subtitle, maxLines = 1, modifier = m)
    }
    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        wheel()
        number(Modifier.padding(top = 4.dp))
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        wheel()
        number(Modifier.padding(start = 8.dp))
    }
}

/** Puntos de volumen que mueve una deslizada completa a lo largo de la rueda (de 0 a 30 son 3 deslizadas). */
private const val VOLUME_POINTS_PER_SWIPE = 10f

/**
 * Aprieta la tecla de volumen dentro de la ventana de LiteDash (se permite sin permisos porque es la
 * propia app): el sistema la intercepta antes, como la del volante o la del panel. En otro hilo:
 * Instrumentation no se puede usar en el principal.
 */
private fun volumeKey(code: Int) {
    thread(name = "volume-key") { runCatching { Instrumentation().sendKeyDownUpSync(code) } }
}

/** El sistema lo manda al cambiar cualquier volumen; no está en el SDK público. */
private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

/**
 * Wi-Fi o datos, audio Bluetooth conectado y ubicación encendida, en la barra.
 * Escucha al sistema solo mientras se ve (nada de revisar cada rato) y sin
 * permisos: el Bluetooth se nota por el audio conectado (tu celular, el estéreo).
 */
@Composable
fun StatusIcons() {
    val c = LocalColors.current
    val context = LocalContext.current
    var network by remember { mutableStateOf<Boolean?>(null) } // true Wi-Fi, false datos, null nada
    var bluetooth by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Siempre se lee la red activa: Android 6 y 7 no avisan cómo está al registrarse (solo
        // cambios) y, escuchando cualquier red, perder una no quiere decir quedarse sin internet.
        fun check(lost: Network? = null) {
            // Al perder una red, Android 6 puede seguir dándola como activa un instante.
            val caps = runCatching { connectivity.activeNetwork?.takeIf { it != lost }?.let(connectivity::getNetworkCapabilities) }.getOrNull()
            network = when {
                caps == null -> null
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
                else -> null
            }
        }
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = check()
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = check()
            override fun onLost(n: Network) = check(lost = n)
        }
        runCatching {
            // Android 6 no tiene "la red de ahora": se escucha cualquier red con internet.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) connectivity.registerDefaultNetworkCallback(netCallback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
        }
        check()

        val audio = context.getSystemService(AudioManager::class.java)
        fun checkAudio() {
            bluetooth = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
        val audioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkAudio()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkAudio()
        }
        audio.registerAudioDeviceCallback(audioCallback, null)
        checkAudio()

        val locations = context.getSystemService(LocationManager::class.java)
        fun checkLocation() {
            location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locations.isLocationEnabled
            else locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        val locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = checkLocation()
        }
        context.listen(locationReceiver, LocationManager.PROVIDERS_CHANGED_ACTION)
        checkLocation()

        onStopOrDispose {
            runCatching { connectivity.unregisterNetworkCallback(netCallback) }
            audio.unregisterAudioDeviceCallback(audioCallback)
            context.unregisterReceiver(locationReceiver)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bluetooth) Icon(Icons.bluetooth, c.textMuted, 20.dp)
        if (location) Icon(Icons.location, c.textMuted, 20.dp)
        when (network) {
            true -> Icon(Icons.wifi, c.textMuted, 20.dp)
            false -> Icon(Icons.signal, c.textMuted, 20.dp)
            null -> Unit
        }
    }
}
