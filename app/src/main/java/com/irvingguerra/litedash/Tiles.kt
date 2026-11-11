package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.fitFont
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.FINE
import com.irvingguerra.litedash.feature.dashboard.SizeLimits
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.music.NotificationService
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.navigation.TurnInstruction
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * Lo que la app sabe de cada tipo de pieza: nombre, tamaño mínimo y con qué tamaño se agrega, en celdas
 * finas (de 48 dp: el doble de las de antes).
 */
data class TileSpec(val type: String, val name: Int, val limits: SizeLimits, val initialSize: Pair<Int, Int>, val detail: Int? = null)

val TileSpecs = listOf(
    TileSpec(TileTypes.CAR, R.string.tile_car, SizeLimits(4, 4), 10 to 6, R.string.tile_car_help),
    TileSpec(TileTypes.ROAD, R.string.tile_road, SizeLimits(4, 4), 10 to 6, R.string.tile_road_help),
    TileSpec(TileTypes.IMAGE, R.string.tile_image, SizeLimits(4, 2), 6 to 4, R.string.tile_image_help),
    TileSpec(TileTypes.MUSIC, R.string.tile_music, SizeLimits(6, 2), 8 to 2, R.string.tile_music_help),
    TileSpec(TileTypes.RADIO, R.string.tile_radio, SizeLimits(6, 2), 8 to 2, R.string.tile_radio_help),
    // También hace lo del widget Navegar (hasta la 1.4.68): en una fila se ve como él (vuelta, Casa, Trabajo).
    TileSpec(TileTypes.MAP, R.string.tile_map, SizeLimits(4, 2), 6 to 4, R.string.tile_map_help),
    TileSpec(TileTypes.CLOCK, R.string.tile_clock, SizeLimits(4, 2), 6 to 2, R.string.tile_clock_help),
    TileSpec(TileTypes.PHONE, R.string.tile_phone, SizeLimits(2, 2), 4 to 2, R.string.tile_phone_help),
    TileSpec(TileTypes.WEATHER, R.string.tile_weather, SizeLimits(4, 2), 6 to 2, R.string.tile_weather_help),
    TileSpec(TileTypes.TRIP, R.string.tile_trip, SizeLimits(4, 2), 6 to 2, R.string.tile_trip_help),
    TileSpec(TileTypes.SPEEDOMETER, R.string.tile_speedometer, SizeLimits(4, 2), 6 to 4, R.string.tile_speedometer_help),
    TileSpec(TileTypes.GAUGE, R.string.tile_gauge, SizeLimits(4, 2), 6 to 4, R.string.tile_gauge_help),
    TileSpec(TileTypes.WORKSHOP, R.string.workshop, SizeLimits(2, 2), 4 to 2, R.string.tile_workshop_help),
    TileSpec(TileTypes.PROJECTION, R.string.tile_projection, SizeLimits(4, 2), 6 to 2, R.string.tile_projection_help),
    TileSpec(TileTypes.TIRES, R.string.tile_tires, SizeLimits(2, 2), 4 to 4, R.string.tile_tires_help),
    TileSpec(TileTypes.APP, R.string.tile_app, SizeLimits(2, 2), 2 to 2, R.string.tile_app_help),
)

fun limitsOf(type: String) = TileSpecs.firstOrNull { it.type == type }?.limits ?: SizeLimits(FINE, FINE)

// Apps de navegación en orden de preferencia (Waze, Google Maps, Maps.me, OsmAnd, HERE).
val NAVIGATION_PACKAGES = arrayOf(
    "com.waze", "com.google.android.apps.maps", "com.mapswithme.maps.pro", "net.osmand", "com.here.app.maps",
)

// Apps de música que se sugieren en el dock si no eligió una (Spotify primero).
val MUSIC_PACKAGES = arrayOf(
    "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
    "com.apple.android.music", "com.amazon.mp3",
)

/** Pantalla chica (7" o menos de alto útil): ahí los íconos crecen para atinarles manejando. */
@Composable
fun isSmallScreen(): Boolean = windowHeightDp() < 640

/** Alto de la ventana en dp (el que de verdad tiene la app, también en pantalla dividida). */
@Composable
private fun windowHeightDp(): Float = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }

/**
 * Muy poca altura (un teléfono acostado): barras y dock compactos y sin aviso,
 * para que al tablero le quede espacio (si no, las piezas salen vacías).
 */
@Composable
fun isShortScreen(): Boolean = windowHeightDp() < 480

/**
 * La hora en números ("3:36"), la marca de 12 h aparte ("p.m.", null en 24 h) para
 * pintarla en chico junto a un reloj grande, y la fecha.
 */
data class Clock(val time: String, val marker: String?, val date: String) {
    val full get() = if (marker == null) time else "$time $marker"
}

/** Hora y fecha que respetan el formato del usuario (12/24 h); un repintado por minuto. */
@Composable
fun rememberClock(): Clock {
    val language = currentLocale()
    val context = LocalContext.current
    // Al volver la app al frente se pone al día (con la pantalla dormida la espera no avanza);
    // también el formato de 12/24 h, que se cambia en Ajustes de Android.
    val starts = rememberStarts()
    val h24 = remember(starts) { DateFormat.is24HourFormat(context) }
    val time = remember(language, h24) {
        // El patrón del idioma sin la marca "a" (va aparte).
        SimpleDateFormat(DateFormat.getBestDateTimePattern(language, if (h24) "Hm" else "hm").replace("a", "").trim(), language)
    }
    val marker = remember(language, h24) { if (h24) null else SimpleDateFormat("a", language) }
    val date = remember(language) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(language, "EEEEdMMMM"), language)
    }
    val now by produceState(Date(), starts) {
        value = Date()
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = Date()
        }
    }
    return Clock(time.format(now), marker?.format(now), date.format(now).replaceFirstChar { it.uppercase() })
}

/** La hora en grande con la marca de 12 h en chico a un lado (si la hay). */
@Composable
fun BigTime(clock: Clock, style: TextStyle) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.Bottom) {
        Label(clock.time, style, maxLines = 1)
        if (clock.marker != null) {
            Label(
                clock.marker,
                Typography.subtitle.copy(fontSize = (style.fontSize.value * 0.32f).coerceAtLeast(14f).sp),
                c.textMuted,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp, bottom = (style.fontSize.value * 0.16f).dp),
            )
        }
    }
}

@Composable
fun ClockTile(driverName: String? = null, weather: WeatherRepository? = null, date: Boolean = true) {
    val c = LocalColors.current
    val clock = rememberClock()
    val hello = greeting(driverName)
    // La fecha y el saludo son opciones del widget (sin los dos, solo la hora).
    val line = listOfNotNull(hello, clock.date.takeIf { date }).joinToString(" · ")
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.CenterStart) {
        // El reloj crece con la pieza: en 1 fila es discreto, en 2 o más es protagonista; lo limita lo que
        // quepa a lo ancho (con la a.m./p.m.) y a lo alto (con la fecha y el clima), para que nunca se corte.
        val tall = maxHeight > 120.dp
        // Solo si ya hay clima que mostrar (con permiso y ya bajado): si no, ni esquina ni píldora vacía.
        val hasWeather = weather != null && weather.active && weather.weather != null
        val extra = if (clock.marker != null) 1.6f else 0.3f
        val grow = fontScale.coerceAtLeast(1f)
        val dateHeight = if (line.isNotEmpty()) CLOCK_DATE_HEIGHT * grow else 0f
        // Lo que queda a lo alto para la hora, con o sin la píldora del clima abajo.
        fun room(pill: Boolean) = maxHeight.value - dateHeight - if (pill) CLOCK_PILL_HEIGHT * grow else 0f
        // En una fila el clima va en la esquina y la hora le deja libre su ancho; con lugar, en una píldora bajo
        // la fecha. Si la hora con su letra más chica no cabe junto a él (o encima de él), va sola: antes se encimaban.
        val corner = CLOCK_WEATHER_WIDTH * grow
        // Ancho (dp) de la hora a cierta letra (dp), con la a.m./p.m. como la dibuja BigTime: su letra no baja de 14.
        fun width(letter: Float) = clock.time.length * 0.6f * letter +
            (clock.marker?.let { it.length * 0.6f * maxOf(letter * 0.32f, 14f * fontScale) + 6f } ?: 0f)
        val withWeather = hasWeather &&
            if (tall) room(pill = true) >= CLOCK_MIN_FONT * CLOCK_LINE else maxWidth.value - corner.value >= width(CLOCK_MIN_FONT)
        val timeWidth = if (withWeather && !tall) maxWidth - corner else maxWidth
        val fits = fitFont(clock.time, timeWidth, maxHeight, if (tall) 0.42f else 0.5f, min = CLOCK_MIN_FONT, max = 104f, extraChars = extra)
        // Que la hora, la fecha y la píldora quepan juntas a lo alto (sin bajar de la letra más chica)…
        var size = minOf(fits, room(withWeather && tall) / CLOCK_LINE / fontScale).coerceAtLeast(CLOCK_MIN_FONT / fontScale)
        // …y a lo ancho con la a.m./p.m. a su tamaño real (con letra chica pesa más de lo que supone fitFont).
        while (size * fontScale > CLOCK_MIN_FONT && width(size * fontScale) > timeWidth.value) size -= 1f
        if (withWeather && !tall) Box(Modifier.align(Alignment.TopEnd)) { WeatherChip(weather) }
        Column {
            BigTime(clock, Typography.clock.copy(fontSize = size.sp))
            if (line.isNotEmpty()) Label(line, Typography.body, c.textMuted, maxLines = 1)
            if (withWeather && tall) Box(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(c.surfaceHigh).padding(horizontal = 12.dp, vertical = 4.dp),
            ) { WeatherChip(weather) }
        }
    }
}

/** Lo que ocupa el clima en la esquina del reloj (ícono y temperatura) y la letra más chica de la hora. */
private val CLOCK_WEATHER_WIDTH = 80.dp
private const val CLOCK_MIN_FONT = 28f
/** Alto de la línea de la fecha y de la píldora del clima con su margen (dp), y alto de renglón de la hora. */
private const val CLOCK_DATE_HEIGHT = 22f
private const val CLOCK_PILL_HEIGHT = 44f
private const val CLOCK_LINE = 1.2f

/**
 * Tarjeta de acceso grande (Teléfono, Android Auto y los widgets sin permiso o sin datos): ícono blanco en
 * un círculo de [tint], título y detalle. [image] pone otra cosa en lugar del círculo (el ícono real de
 * una app) y [chevron] una flecha tenue a la derecha.
 */
@Composable
fun ShortcutTile(
    icon: ImageVector,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    tint: Color = LocalColors.current.accent,
    image: (@Composable (Dp) -> Unit)? = null,
    chevron: Boolean = false,
) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            val large = maxHeight > 120.dp
            val small = isSmallScreen()
            val width = maxWidth
            val side = if (large) 64.dp else if (small) 52.dp else 44.dp
            val content = @Composable {
                if (image != null) image(side)
                else Box(Modifier.size(side).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                    // Blanco sobre el color del acceso (verde, ámbar…): se lee en claro y en oscuro.
                    Icon(icon, Color.White, side * 0.47f)
                }
            }
            val texts = @Composable { m: Modifier ->
                Column(m) {
                    Label(title, Typography.subtitle, maxLines = 1)
                    if (detail != null) Label(detail, Typography.small, c.textMuted, maxLines = 1)
                }
            }
            val arrow = @Composable { if (chevron) Icon(Icons.chevron, c.textMuted.copy(alpha = 0.6f), 20.dp) }
            if (large) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    content()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        texts(Modifier.weight(1f))
                        arrow()
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    content()
                    if (width > 120.dp) {
                        texts(Modifier.weight(1f).padding(start = 12.dp))
                        arrow()
                    }
                }
            }
        }
    }
}

/**
 * Una app como pieza: el ícono llena la pieza (solo deja lugar al nombre, si cabe) y, sin fondo,
 * casi hasta la orilla. Vacía, invita a elegir cuál.
 */
@Composable
fun AppTile(repo: AppRepository, app: App?, named: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (LocalPlainCard.current) 2.dp else 6.dp), contentAlignment = Alignment.Center) {
            // El nombre, si se quiere y si cabe.
            val withName = named && maxHeight > 90.dp
            val width = maxWidth
            val side = minOf(maxWidth * 0.8f, if (withName) maxHeight - 26.dp else maxHeight * 0.86f).coerceIn(24.dp, 140.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (app == null) {
                    Icon(Icons.plus, c.textMuted, side * 0.5f)
                    Spacer(Modifier.height(4.dp))
                    Label(stringResource(R.string.pick_app), Typography.small, c.textMuted, maxLines = 1)
                } else {
                    AppIcon(repo, app, side)
                    if (withName) {
                        Spacer(Modifier.height(4.dp))
                        // A lo ancho de la pieza y centrado: si no cabe, se corta parejo con "…".
                        Label(app.name, Typography.small.copy(textAlign = TextAlign.Center), maxLines = 1, modifier = Modifier.width(width))
                    }
                }
            }
        }
    }
}

/**
 * El mapa sin mapa en vivo (en una fila o sin permiso de ubicación). Sin ruta: acceso a la app de
 * mapas (y a Casa y Trabajo) sobre un mapa dibujado. Navegando: la siguiente vuelta que dice el
 * aviso de Waze o Maps (flecha, distancia y calle), sin abrir el mapa.
 */
@Composable
fun NavigationTile(
    repo: NavigationRepository,
    app: App?,
    home: String?,
    work: String?,
    onPlace: (String) -> Unit,
    onClick: () -> Unit,
    /** Lugar libre a la derecha (el botón para pedir la ubicación va ahí). */
    endInset: Dp = 0.dp,
) {
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val instruction = repo.instruction
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding).padding(end = endInset), contentAlignment = Alignment.CenterStart) {
            val large = maxHeight > 120.dp
            val wide = maxWidth >= 380.dp
            if (instruction != null) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // La vuelta se encoge (con "…") y Casa y Trabajo van a su lado, si caben, sin encimarse.
                Box(Modifier.weight(1f).padding(4.dp)) { Turn(instruction, large) }
                if (wide) PlacePills(home, work, onPlace, Modifier.padding(start = 8.dp), background = LocalColors.current.surfaceHigh)
            } else Places(app, home, work, onPlace, tall = maxHeight > 150.dp, width = maxWidth, height = maxHeight)
        }
    }
}

/**
 * Sin ruta: el mapa en miniatura (como la carátula de la música), el título y los
 * atajos a Casa y Trabajo (un toque y el mapa ya va para allá).
 */
@Composable
private fun Places(app: App?, home: String?, work: String?, onPlace: (String) -> Unit, tall: Boolean, width: Dp, height: Dp) {
    val c = LocalColors.current
    val places = @Composable {
        if (home != null) Button(stringResource(R.string.place_home), { onPlace(home) })
        if (work != null) Button(stringResource(R.string.place_work), { onPlace(work) })
    }
    val title = @Composable {
        Column {
            Label(stringResource(R.string.tile_map), if (tall) Typography.title else Typography.subtitle, maxLines = 1)
            if (app != null) Label(app.name, Typography.small, c.textMuted, maxLines = 1)
        }
    }
    val hasPlaces = home != null || work != null
    if (tall && height >= 240.dp) {
        // Alta: el mapa arriba a lo ancho y abajo el título con los atajos.
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MapArt(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(Radius.inner)))
            if (hasPlaces && width < 480.dp) {
                // Angosta: el título y, debajo, los atajos.
                Box(Modifier.padding(start = 4.dp)) { title() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { places() }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = 4.dp)) { title() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { places() }
                }
            }
        }
        return
    }
    val thumb = minOf(height, 180.dp)
    // Angosta con atajos: el mapa y los botones (ellos dicen a dónde); el título, si cabe.
    // Muy angosta: sin mapa, para que "Casa" y "Trabajo" no se corten.
    val withMap = !hasPlaces || width - thumb >= 180.dp
    val withTitle = !hasPlaces || width - thumb >= 360.dp
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (withMap) MapArt(Modifier.size(thumb).clip(RoundedCornerShape(Radius.inner)))
        if (withTitle) Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { title() }
        else Spacer(Modifier.weight(1f))
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { places() }
    }
}

/**
 * Un mapa sugerido: calles y una ruta del color de la interfaz que llega a su pin. Dibujado, así que
 * no pesa nada ni necesita internet; se ve igual en claro y en oscuro.
 */
@Composable
private fun MapArt(modifier: Modifier) {
    val c = LocalColors.current
    Canvas(modifier.background(c.surfaceHigh)) {
        val w = size.width
        val h = size.height
        val road = (minOf(w, h) * 0.08f).coerceIn(4f, 14f)
        val main = c.textMuted.copy(alpha = 0.22f)
        val minor = c.textMuted.copy(alpha = 0.12f)
        drawLine(minor, Offset(0f, h * 0.30f), Offset(w, h * 0.22f), road * 0.6f)
        drawLine(main, Offset(0f, h * 0.70f), Offset(w, h * 0.64f), road)
        drawLine(main, Offset(w * 0.30f, 0f), Offset(w * 0.34f, h), road)
        drawLine(minor, Offset(w * 0.74f, 0f), Offset(w * 0.70f, h), road * 0.6f)
        // La ruta: sube por una calle, da vuelta y llega al pin.
        val corner = Offset(w * 0.335f, h * 0.67f)
        val pin = Offset(w * 0.71f, h * 0.36f)
        val route = Path().apply {
            moveTo(w * 0.34f, h)
            lineTo(corner.x, corner.y + road)
            quadraticTo(corner.x, corner.y, corner.x + road * 1.5f, corner.y - road * 0.1f)
            lineTo(w * 0.70f - road, h * 0.645f)
            quadraticTo(w * 0.705f, h * 0.645f, w * 0.705f, h * 0.645f - road)
            lineTo(pin.x, pin.y)
        }
        drawPath(route, c.accent, style = Stroke(road * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.accent, road * 1.6f, pin)
        drawCircle(c.surfaceHigh, road * 0.65f, pin)
    }
}

/** Flecha del color de la interfaz, distancia y calle de la siguiente vuelta. */
@Composable
internal fun Turn(instruction: TurnInstruction, large: Boolean) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TurnArrow(instruction, if (large) 72.dp else 48.dp, Radius.card)
        Column(Modifier.padding(start = 14.dp)) {
            if (instruction.title.isNotBlank()) Label(instruction.title, if (large) Typography.title.copy(fontSize = 28.sp) else Typography.title, maxLines = 1)
            if (instruction.text.isNotBlank()) Label(instruction.text, Typography.body, maxLines = if (large) 2 else 1)
            if (large && instruction.detail.isNotBlank()) Label(instruction.detail, Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/** El cuadro del color de la interfaz con la flecha del aviso (o la de navegar, si no trae). */
@Composable
internal fun TurnArrow(instruction: TurnInstruction, side: Dp, radius: Dp) {
    val c = LocalColors.current
    Box(Modifier.size(side).clip(RoundedCornerShape(radius)).background(c.accent), contentAlignment = Alignment.Center) {
        val arrow = instruction.arrow
        if (arrow != null) {
            Image(remember(arrow) { arrow.asImageBitmap() }, null, Modifier.size(side * 0.7f), colorFilter = ColorFilter.tint(c.onPrimary))
        } else {
            Icon(Icons.navigate, c.onPrimary, side * 0.5f)
        }
    }
}

/** Casa y Trabajo (los que estén guardados) como píldoras: un toque y el mapa ya va para allá. */
@Composable
internal fun PlacePills(home: String?, work: String?, onPlace: (String) -> Unit, modifier: Modifier = Modifier, background: Color = LocalColors.current.surface) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (home != null) PlacePill(stringResource(R.string.place_home), background) { onPlace(home) }
        if (work != null) PlacePill(stringResource(R.string.place_work), background) { onPlace(work) }
    }
}

@Composable
private fun PlacePill(text: String, background: Color, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.navigate, c.accent, 14.dp)
        Label(text, Typography.small.copy(fontWeight = FontWeight.Medium), maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

fun openDialer(context: Context) {
    val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Navegación directa a [address]: Waze o Maps si son la app elegida; si no, la que atienda "geo:". */
fun navigateTo(context: Context, app: App?, address: String) {
    val q = Uri.encode(address)
    val intent = when (app?.pkg) {
        "com.waze" -> Intent(Intent.ACTION_VIEW, "https://waze.com/ul?q=$q&navigate=yes".toUri()).setPackage("com.waze")
        "com.google.android.apps.maps" -> Intent(Intent.ACTION_VIEW, "google.navigation:q=$q".toUri()).setPackage(app.pkg)
        else -> Intent(Intent.ACTION_VIEW, "geo:0,0?q=$q".toUri()).apply { app?.let { setPackage(it.pkg) } }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (app != null && SplitScreen.open(context, intent, app.pkg)) return
    runCatching { context.startActivity(intent) }.onFailure {
        // La app elegida no entendió la dirección: que la abra cualquiera que sepa de mapas.
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$q".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Abre la navegación: la app elegida, o la que haya instalada, o lo que atienda "geo:". */
fun openNavigation(context: Context, repo: AppRepository, app: App?) {
    if (app != null) return repo.open(context, app)
    val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Agrega una pieza de [type] con su tamaño inicial en el primer hueco; false si ya no cabe. */
fun DashboardState.addTile(type: String, data: String = ""): Boolean {
    val spec = TileSpecs.first { it.type == type }
    return add(spec.type, spec.initialSize, spec.limits, data)
}

/** Encabezado de un widget: el nombre en chico a la izquierda y el estado con su punto a la derecha. */
@Composable
internal fun TileHeader(title: String, status: String, color: Color) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(title.uppercase(), Typography.label, c.textMuted, maxLines = 1)
        // El estado cede: se corta con "…" antes que encimarse con el nombre.
        Row(Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Label(status, Typography.small, color, maxLines = 1, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
        }
    }
}
