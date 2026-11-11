// Ajustes → Cuenta: el QR para vincular LiteDash Connect (donde vive la cuenta) y, ya vinculado, cómo va el enlace.
package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.link.Pairing
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.QrCode

/** Lado del QR a lo ancho; angosto se ajusta a lo que haya. */
private val QR_SIZE = 320.dp

/** Desde este ancho el QR va a un lado de los pasos; más angosto, arriba. */
private val SIDE_BY_SIDE = 660.dp

/** Módulos claros alrededor del QR (lo que pide la norma para que la cámara lo encuentre). */
private const val QUIET_ZONE = 4

/**
 * La sección Cuenta. Mientras está a la vista el enlace intenta conectarse cada 2 s
 * y mantiene vigente el QR; al salir (o irse LiteDash al fondo) deja de intentar.
 */
@Composable
internal fun AccountSettings(link: PhoneLink) {
    LifecycleResumeEffect(link) {
        link.watch(true)
        onPauseOrDispose { link.watch(false) }
    }
    val pairing = link.pairing
    SettingsCard(help = stringResource(R.string.account_help)) {
        if (link.paired) Linked(link) else if (pairing != null) PairingCode(pairing)
    }
}

/** Sin celular: el QR y los tres pasos. */
@Composable
private fun PairingCode(pairing: Pairing) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        if (width >= SIDE_BY_SIDE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                QrImage(pairing.text, QR_SIZE)
                Steps(pairing.code, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                QrImage(pairing.text, minOf(width, QR_SIZE), Modifier.align(Alignment.CenterHorizontally))
                Steps(pairing.code, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Steps(code: String, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier) {
        Label(stringResource(R.string.phone_link_title), Typography.title)
        // Los dígitos separados: se comparan de un vistazo con los del celular.
        Label(stringResource(R.string.phone_link_code, code.toList().joinToString(" ")), Typography.body, c.textMuted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        val steps = listOf(R.string.phone_step_install, R.string.phone_step_tap, R.string.phone_step_scan)
        steps.forEachIndexed { i, step ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("${i + 1}", Typography.body, c.accent)
                Label(stringResource(step), Typography.body)
            }
        }
    }
}

/** El QR: siempre negro sobre blanco (también en tema oscuro) y con módulos de píxeles enteros. */
@Composable
private fun QrImage(text: String, side: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encode(text.toByteArray()) } ?: return
    Canvas(modifier.size(side).clip(RoundedCornerShape(Radius.card)).background(Color.White)) {
        val module = (size.minDimension / (qr.size + QUIET_ZONE * 2)).toInt().coerceAtLeast(1)
        val start = ((size.minDimension - module * qr.size) / 2).toInt()
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                // Cada corrida oscura del renglón, de un solo trazo.
                val from = x
                while (x < qr.size && qr[x, y]) x++
                if (x > from) drawRect(Color.Black, Offset((start + from * module).toFloat(), (start + y * module).toFloat()), Size(((x - from) * module).toFloat(), module.toFloat()))
                x++
            }
        }
    }
}

/** Con celular vinculado: si hay conexión, cuál es y el botón para desvincularlo. */
@Composable
private fun Linked(link: PhoneLink) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (link.connected) c.accent else c.line))
        Label(stringResource(if (link.connected) R.string.phone_connected else R.string.phone_offline), Typography.title, maxLines = 1)
    }
    Column {
        Fact(stringResource(R.string.phone_name), link.phoneName)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Fact(stringResource(R.string.phone_app_version), link.phoneApp)
    }
    // La música del celular necesita un permiso que solo se da en el celular.
    if (link.connected && link.musicBlocked) Label(stringResource(R.string.phone_music_blocked), Typography.body, c.textMuted)
    Row { Button(stringResource(R.string.phone_unlink), link::unlink) }
}

/** Un dato del celular: nombre a la izquierda y valor a la derecha. */
@Composable
private fun Fact(name: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(name, Typography.body, LocalColors.current.textMuted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Label(value?.ifBlank { null } ?: "—", Typography.body.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}
