package com.irvingguerra.litedash.core.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Íconos propios en una cuadrícula de 24: trazo de 2 con puntas redondas, y
 * los de reproducción rellenos. Sin la librería de íconos de Material (pesa megas).
 */
object Icons {
    val car = icon(
        "M3 16v-3.5l2.4-4.8a1.5 1.5 0 0 1 1.3-.7h8.6a1.5 1.5 0 0 1 1.2.6l3.5 4.4h1a1 1 0 0 1 1 1v3 M9 16h6 M3 16h1.5 M19.5 16h1.5 M5 12h15",
        "M5 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M15 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )
    /** Carretera en perspectiva con su raya al centro. */
    val road = icon("M8 3l-4 18 M16 3l4 18 M12 4v3 M12 10.5v3 M12 17v3")
    /** Foto: marco con una montaña y el sol. */
    val image = icon("M5 5h14a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-12a1 1 0 0 1 1 -1z M4 16l5 -5l4 4l2 -2l5 5 M14.5 9a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0")
    val apps = icon("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M14 14h6v6h-6z")
    val settings = icon("M4 6h8 M16 6h4 M14 4v4 M4 12h2 M10 12h10 M8 10v4 M4 18h11 M19 18h1 M17 16v4")
    val minus = icon("M6 12h12")
    /** Tarjeta tachada: quitar o poner el fondo de una pieza. */
    val noCard = icon("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z M4 20l16 -16")
    val plus = icon("M12 6v12 M6 12h12")
    val close = icon("M7 7l10 10 M17 7l-10 10")
    val swap = icon("M5 8h13 M15 5l3 3l-3 3 M19 16h-13 M9 13l-3 3l3 3")
    val expand = icon("M4 9v-5h5 M20 9v-5h-5 M4 15v5h5 M20 15v5h-5")
    val done = icon("M5 12.5l4.5 4.5l9.5 -10")
    val volume = icon("M4 9.5h3.5l4.5 -4v13l-4.5 -4h-3.5z M15.5 9a4 4 0 0 1 0 6 M18 6.5a7.5 7.5 0 0 1 0 11")
    val mic = icon("M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1 -6 0z M5 11a7 7 0 0 0 14 0 M12 18v3")
    val bluetooth = icon("M7 7l10 10l-5 4v-18l5 4l-10 10")
    val wifi = icon("M2 8.5a15 15 0 0 1 20 0 M5 12a10 10 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 19h.01")
    val signal = icon("M5 19v-2 M10 19v-5 M15 19v-8 M20 19v-11")
    val location = icon("M12 21s-6 -5.5 -6 -10.5a6 6 0 0 1 12 0c0 5 -6 10.5 -6 10.5z M12 10.5h.01")
    // Clima: dibujados en código, sin imágenes.
    val sun = icon("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0 -8 M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4 -1.4 M17.7 6.3l1.4 -1.4")
    val moon = icon("M20 14.5a8 8 0 1 1 -10.5 -10.5a6.5 6.5 0 0 0 10.5 10.5z")
    val cloud = icon("M7 18h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z")
    val partlyCloudy = icon("M8 4v1 M3.5 6l.7 .7 M2 10.5h1 M5 10.5a3.5 3.5 0 0 1 6.5 -1.7 M8 20h9a3.5 3.5 0 0 0 0 -7a5 5 0 0 0 -9.6 1.3a2.9 2.9 0 0 0 .6 5.7z")
    val rain = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M8 18l-1 3 M12 18l-1 3 M16 18l-1 3")
    val storm = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M13 15l-2 3.5h3l-2 3.5")
    val snow = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M8 19h.01 M12 20h.01 M16 19h.01 M10 22h.01 M14 22h.01")
    val fog = icon("M4 9h16 M3 13h18 M5 17h14 M8 21h8")
    /** Persona en un círculo: la cuenta. */
    val account = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M6.2 18.7a6.5 6.5 0 0 1 11.6 0")
    val bell = icon("M6 16v-5a6 6 0 0 1 12 0v5l2 2h-16z M10 21h4")
    val phoneCar = icon("M8 3h8a1 1 0 0 1 1 1v9a1 1 0 0 1 -1 1h-8a1 1 0 0 1 -1 -1v-9a1 1 0 0 1 1 -1z M11 11h2 M4 21l1.5 -4h13l1.5 4 M6 21h.01 M18 21h.01")
    // Motor (como la luz de "check engine"): el Taller del escáner OBD.
    val engine = icon("M3 10v6 M3 13h2 M5 9h2l1 -2h5l1 2h3l2 2h2v6h-2l-2 2h-8l-2 -3h-2z M9 4h4 M11 4v3")
    val speed = icon("M4.5 17a8.5 8.5 0 1 1 15 0 M12 14l3.5 -4.5 M12 14h.01")
    val tachometer = icon("M4.5 17a8.5 8.5 0 1 1 15 0 M12 14l-3 -4.5 M12 14h.01 M16 10.5l1 -1")
    val thermometer = icon("M10 13.5v-8.5a2 2 0 0 1 4 0v8.5a4 4 0 1 1 -4 0z M12 9v7")
    val fuel = icon("M4 20v-14a2 2 0 0 1 2 -2h6a2 2 0 0 1 2 2v14 M3 20h12 M4 11h10 M14 9h2a2 2 0 0 1 2 2v5a1.5 1.5 0 0 0 3 0v-8l-3 -3")
    val battery = icon("M4 8h16v11h-16z M7 8v-2h3v2 M14 8v-2h3v2 M7 13h3 M14 13h3 M15.5 11.5v3")
    val drop = icon("M12 3.5s6 6.5 6 11a6 6 0 0 1 -12 0c0 -4.5 6 -11 6 -11z")
    /** Pedal del acelerador: la placa inclinada y su base. */
    val pedal = icon("M10 3.5l4 1l-3 13.5l-4 -1z M9.8 8l2.8 .7 M5 20.5h14 M9 17.5l1 3")
    /** Aceite: la gota sobre una línea. */
    val oil = icon("M12 3s5 5.5 5 9a5 5 0 0 1 -10 0c0 -3.5 5 -9 5 -9z M4 21h16")
    val turbo = icon("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0 M12 12q0 -4 3.5 -5.5 M12 12q4 0 5.5 3.5 M12 12q0 4 -3.5 5.5 M12 12q-4 0 -5.5 -3.5")
    val warning = icon("M12 4l9 16h-18z M12 10v4 M12 17h.01")
    val navigate = icon("M12 3l7.5 17.5l-7.5 -4l-7.5 4z")
    /** Mapa doblado en tres. */
    val map = icon("M3 6.5l6 -2.5l6 2.5l6 -2.5v13.5l-6 2.5l-6 -2.5l-6 2.5z M9 4v13.5 M15 6.5v13.5")
    val phone = icon("M5 4h4l2 5l-2.5 1.5a11 11 0 0 0 5 5l1.5 -2.5l5 2v4a2 2 0 0 1 -2 2a16 16 0 0 1 -15 -15a2 2 0 0 1 2 -2")
    val music = icon("M9 17v-12l10 -2v12 M3 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M13 15a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val search = icon("M4 10a6 6 0 1 0 12 0a6 6 0 1 0 -12 0 M20 20l-5.5 -5.5")
    /** Lista (biblioteca de música): tres renglones con su punto. */
    val list = icon("M9 6h11 M9 12h11 M9 18h11 M4 6h.01 M4 12h.01 M4 18h.01")
    /** Carpeta de la memoria. */
    val folder = icon("M3 7a2 2 0 0 1 2 -2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2z")
    /** Memoria USB: el conector y el cuerpo. */
    val usb = icon("M9 3h6v5h-6z M11 5h.5 M13 5h-.5 M7 8h10v12a1 1 0 0 1 -1 1h-8a1 1 0 0 1 -1 -1z")
    /** Radio: la caja, la antena y la bocina. */
    val radio = icon("M4 9h16a1 1 0 0 1 1 1v9a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-9a1 1 0 0 1 1 -1z M6 9l10 -5 M7 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0 M15 13h3 M15 16h3")
    val star = icon("M12 3.5l2.6 5.4l5.9 .8l-4.3 4.1l1.1 5.9l-5.3 -2.8l-5.3 2.8l1.1 -5.9l-4.3 -4.1l5.9 -.8z")
    val starFilled = icon(fills = listOf("M12 3.5l2.6 5.4l5.9 .8l-4.3 4.1l1.1 5.9l-5.3 -2.8l-5.3 2.8l1.1 -5.9l-4.3 -4.1l5.9 -.8z"))
    val corner = icon("M19 11v8h-8 M19 19l-7 -7")
    val dashboard = icon("M4 4h7v9h-7z M13 4h7v5h-7z M13 11h7v9h-7z M4 15h7v5h-7z")
    val openExternal = icon("M14 4h6v6 M20 4l-9 9 M18 14v6h-14v-14h6")
    val info = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 11v5 M12 7.5v.5")
    /** Flecha para entrar (›). */
    val chevron = icon("M9 6l6 6l-6 6")
    val back = icon("M9 14l-5 -5l5 -5 M4 9h11a5 5 0 0 1 0 10h-3")
    /** Volante: aro, centro y tres rayos. */
    val wheel = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M3.5 10.5l6.5 1 M14 11.5l6.5 -1 M12 14v7")
    /** Llanta de lado: aro, rin y cuatro birlos. */
    val tire = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M7.5 12a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0 M12 9.5h.01 M12 14.5h.01 M9.5 12h.01 M14.5 12h.01")
    /** El testigo del tablero de presión de llantas: la llanta en corte con un signo de admiración. */
    val tireAlert = icon("M7 4.5c-2.6 2.2 -3.8 4.8 -3.8 7.8c0 2.6 .9 4.7 2.6 6.7 M17 4.5c2.6 2.2 3.8 4.8 3.8 7.8c0 2.6 -.9 4.7 -2.6 6.7 M5.5 19.5h13 M12 8v5 M12 16h.01")
    val clock = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 7v5l3 3")
    val play = icon(fills = listOf("M7 4.5v15a1 1 0 0 0 1.5 .9l12 -7.5a1 1 0 0 0 0 -1.8l-12 -7.5a1 1 0 0 0 -1.5 .9z"))
    val pause = icon(fills = listOf("M6 4h4v16h-4z M14 4h4v16h-4z"))
    val previous = icon("M5 5v14", fills = listOf("M20 5.8v12.4a1 1 0 0 1 -1.6 .8l-8.6 -6.2a1 1 0 0 1 0 -1.6l8.6 -6.2a1 1 0 0 1 1.6 .8z"))
    val next = icon("M19 5v14", fills = listOf("M4 5.8v12.4a1 1 0 0 0 1.6 .8l8.6 -6.2a1 1 0 0 0 0 -1.6l-8.6 -6.2a1 1 0 0 0 -1.6 .8z"))

    private fun icon(vararg strokes: String, fills: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (t in strokes) b.addPath(
            addPathNodes(t),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        for (r in fills) b.addPath(addPathNodes(r), fill = SolidColor(Color.Black))
        return b.build()
    }
}

// El color y el tamaño van primero a propósito: así se escriben en toda la app (Icon(x, c.accent, 22.dp)).
@SuppressLint("ModifierParameter")
@Composable
fun Icon(icon: ImageVector, color: Color = LocalColors.current.text, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}
