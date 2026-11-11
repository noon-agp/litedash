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
 * Volumen de la música en la barra como una rueda táctil (dial): se desliza el dedo en círculo sobre ella;
 * a la derecha (en el sentido del reloj) sube y a la izquierda baja. Cada "clic" de la rueda mueve el volumen
 * [VOLUME_STEP] puntos y vibra un poco. Muestra el nivel con un arco y con el número al centro.
 * Con el volumen del chip de la pantalla (fijo para Android) manda las teclas de volumen, como el botón físico.
 */
@Composable
fun Volume(buttonSize: Dp, vertical: Boolean = false) {
    val c = LocalColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
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
    // Un clic de la rueda: sube (+1) o baja (-1) [VOLUME_STEP] puntos.
    fun stepBy(direction: Int) {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (before + direction * VOLUME_STEP).coerceIn(0, max)
        if (!audio.isVolumeFixed && target != before) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Si Android no lo dejó cambiar (la pantalla maneja su propio chip), las teclas de volumen.
        if (audio.isVolumeFixed || (level == before && target != before)) {
            repeat(VOLUME_STEP) { volumeKey(if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN) }
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val fixed = audio.isVolumeFixed
    val dial = if (vertical) buttonSize * 1.15f else buttonSize
    Box(
        Modifier
            .size(dial)
            .clip(CircleShape)
            .background(c.surfaceHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    var last = atan2(down.position.y - cy, down.position.x - cx)
                    var turned = 0f
                    while (true) {
                        val move = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        move.consume()
                        val now = atan2(move.position.y - cy, move.position.x - cx)
                        var delta = now - last
                        // Cruzar de +180° a -180° no es un giro enorme.
                        if (delta > PI.toFloat()) delta -= 2f * PI.toFloat() else if (delta < -PI.toFloat()) delta += 2f * PI.toFloat()
                        last = now
                        turned += delta
                        while (turned >= VOLUME_CLICK_RAD) { stepBy(1); turned -= VOLUME_CLICK_RAD }
                        while (turned <= -VOLUME_CLICK_RAD) { stepBy(-1); turned += VOLUME_CLICK_RAD }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val thick = 4.dp.toPx()
            val arcTopLeft = Offset(thick / 2, thick / 2)
            val arcSize = Size(size.width - thick, size.height - thick)
            // Un arco de 270° (abierto abajo): el fondo y, encima, lo que lleva el volumen.
            drawArc(color = c.line, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(width = thick, cap = StrokeCap.Round))
            val sweep = 270f * (level.toFloat() / max).coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(color = c.primary, startAngle = 135f, sweepAngle = sweep, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(width = thick, cap = StrokeCap.Round))
        }
        if (fixed) Icon(Icons.volume, c.text, dial * 0.4f)
        else Label(level.toString(), Typography.subtitle, maxLines = 1)
    }
}

/** Cuántos puntos de volumen mueve cada clic de la rueda. */
private const val VOLUME_STEP = 5

/** Cuánto se gira la rueda por cada clic (en radianes: 0.7 son unos 40°). */
private const val VOLUME_CLICK_RAD = 0.7f

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
