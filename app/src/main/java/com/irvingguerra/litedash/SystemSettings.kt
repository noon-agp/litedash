// Ajustes → Sistema y Módulos: actualizaciones, Android, tu pantalla, módulos y permisos.
package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Switch
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.updater.Version
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pestañas de Sistema; Actualizaciones va primero (ahí abre el aviso de versión nueva). */
internal enum class SystemTab(val title: Int) {
    UPDATES(R.string.tab_updates), ANDROID(R.string.android_settings), STATS(R.string.stats_title), YOUR_SCREEN(R.string.tab_your_screen),
}

/**
 * Sistema: lo de LiteDash y de la pantalla en pestañas. Actualizaciones (la versión con sus
 * novedades y, en la de GitHub, buscar la siguiente), atajos a Ajustes de Android, estadísticas
 * anónimas e info de tu pantalla.
 */
@Composable
internal fun SystemSettings(d: Dependencies) {
    when (SystemTab.entries[tabs(SystemTab.entries.map { it.title }, SYSTEM_TABS).coerceAtMost(SystemTab.entries.lastIndex)]) {
        SystemTab.UPDATES -> {
            About(d)
            if (d.updater.available) SettingsCard { Updates(d.updater) }
        }
        SystemTab.ANDROID -> AndroidSettings()
        SystemTab.STATS -> SettingsCard {
            SwitchRow(stringResource(R.string.stats_switch), d.prefs.stats, { d.prefs.stats = it }, stringResource(R.string.stats_help))
        }
        SystemTab.YOUR_SCREEN -> ScreenInfo(d)
    }
}

/** La pantalla en unas líneas, LiteDash Pro ([ProCard]) y "Enviar un reporte" ([SendReport]). */
@Composable
private fun ScreenInfo(d: Dependencies) {
    val context = LocalContext.current
    SettingsCard {
        val lines = remember { screenLines(context) }
        for (line in lines) Label(line, Typography.body, maxLines = 2)
    }
    ProCard(d.pro)
    SendReport(d.music, d.pro, d.dashboard, d::obdIfCreated) { d.phone.describe() }
}

/** LiteDash Pro en prueba: activarlo con un código o, si ya está, quitarlo de esta pantalla (solo lo local). */
@Composable
private fun ProCard(pro: ProAccess) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    SettingsCard(stringResource(R.string.pro_title)) {
        if (pro.isPro) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.done, c.accent, 24.dp)
                Label(stringResource(R.string.pro_active), Typography.body)
            }
            Confirmed(stringResource(R.string.pro_remove), stringResource(R.string.pro_remove_question)) { scope.launch { pro.remove() } }
        } else {
            Label(stringResource(R.string.pro_help), Typography.small, c.textMuted)
            TextInput(code, { code = it.take(PRO_CODE_MAX); error = null }, stringResource(R.string.pro_code), Modifier.fillMaxWidth())
            val idle = !checking && ProCode.normalize(code).isNotEmpty()
            Button(
                stringResource(if (checking) R.string.pro_checking else R.string.pro_activate),
                {
                    if (!idle) return@Button
                    checking = true
                    error = null
                    scope.launch {
                        error = when (pro.activate(code)) {
                            ProResult.ACTIVE -> null.also { code = "" }
                            ProResult.INVALID -> R.string.pro_invalid
                            ProResult.TAKEN -> R.string.pro_taken
                            ProResult.OFFLINE -> R.string.pro_offline
                        }
                        checking = false
                    }
                },
                Modifier.graphicsLayer { alpha = if (idle) 1f else 0.45f },
                primary = true,
            )
            (error ?: R.string.pro_expired.takeIf { pro.expired })?.let { Label(stringResource(it), Typography.small, c.danger) }
        }
    }
}

/** Largo máximo de lo que se escribe como código. */
private const val PRO_CODE_MAX = 64

/** La versión en grande, sus novedades y tu auto a la derecha (como DuduAuto). */
@Composable
private fun About(d: Dependencies) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 560.dp
            val pkg = d.cars.pkg(d.prefs.carModel)
            if (pkg != null && wide) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                CarStill(d.cars, pkg, d.prefs.carPaint, Modifier.fillMaxWidth(0.46f).aspectRatio(CAR_ASPECT))
            }
            Column(Modifier.padding(22.dp).fillMaxWidth(if (pkg != null && wide) 0.56f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("LiteDash ${BuildConfig.VERSION_NAME}", Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                Label(BuildConfig.CHANNEL, Typography.small, c.textMuted)
                if (BuildConfig.RELEASE_NOTES.isNotBlank()) {
                    Label(stringResource(R.string.release_notes), Typography.label, c.textMuted)
                    for (line in remember { releaseNotes() }) {
                        if (line.startsWith("###")) Label(line.removePrefix("###").trim(), Typography.subtitle, maxLines = 1)
                        else Label("• $line", Typography.small)
                    }
                }
            }
        }
    }
}

/**
 * Módulos, en dos pestañas. Módulos: cada uno con su interruptor y, prendido, una flecha a sus
 * ajustes; apagado en gris, no corre ni pide nada y desaparece de todo. Permisos: los que se dan
 * con Android (avisos de otras apps y ubicación), con el mismo interruptor.
 */
@Composable
internal fun Modules(d: Dependencies, revision: Int, open: (SettingsSection) -> Unit) {
    val permissions = tabs(listOf(R.string.modules, R.string.tab_permissions), "modules") == 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (permissions) return@Column Permissions(d, revision)
        ModuleRow(Icons.engine, stringResource(R.string.module_obd), d.obdModule, { d.obdModule = it }) { open(SettingsSection.WORKSHOP) }
        // Llantas: prendido = hay una fuente (sin crear el módulo para saberlo). Al prender vuelve
        // la última que se usó; si nunca hubo, a elegirla en Ajustes → Llantas.
        ModuleRow(Icons.tire, stringResource(R.string.module_tpms), d.hasTpmsSource, { on ->
            val tpms = d.tpms
            if (!on) tpms.source = null
            else tpms.lastSource?.let { tpms.source = it } ?: open(SettingsSection.TIRES)
        }) { open(SettingsSection.TIRES) }
        ModuleRow(Icons.car, stringResource(R.string.module_cameras), on = false, onChange = null, soon = true)
    }
}

/**
 * Módulos → Permisos: prender los pide; apagar solo se puede en Ajustes de Android. [revision]
 * cambia al volver a la app: lo que se dio o quitó allá se nota al regresar.
 */
@Composable
private fun Permissions(d: Dependencies, revision: Int) {
    val context = LocalContext.current
    val notifications = remember(revision) { d.music.hasPermission() }
    val (location, askLocation) = rememberGpsPermission(d, revision)
    ModuleRow(Icons.bell, stringResource(R.string.module_notifications), notifications, { on ->
        if (on) d.music.requestPermission() else openAppSettings(context)
    })
    ModuleRow(Icons.location, stringResource(R.string.module_location), location, { on ->
        if (on) askLocation() else openAppSettings(context)
    })
}

/**
 * Atajos a los ajustes del sistema: muchas pantallas de auto esconden sus ajustes detrás
 * del launcher de la marca, y al poner LiteDash de inicio ya no se encuentran.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidSettings() {
    val c = LocalColors.current
    val context = LocalContext.current
    val shortcuts = remember {
        listOf(
            R.string.sys_all to Settings.ACTION_SETTINGS,
            R.string.sys_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
            R.string.sys_wifi to Settings.ACTION_WIFI_SETTINGS,
            R.string.sys_location to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            R.string.sys_display to Settings.ACTION_DISPLAY_SETTINGS,
            R.string.sys_sound to Settings.ACTION_SOUND_SETTINGS,
            R.string.sys_apps to Settings.ACTION_APPLICATION_SETTINGS,
            R.string.sys_date to Settings.ACTION_DATE_SETTINGS,
            R.string.sys_language to Settings.ACTION_LOCALE_SETTINGS,
            R.string.sys_home to Settings.ACTION_HOME_SETTINGS,
        ).filter { (_, action) -> Intent(action).resolveActivity(context.packageManager) != null }
    }
    SettingsCard(stringResource(R.string.android_settings), stringResource(R.string.android_settings_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, action) in shortcuts) {
                Button(stringResource(name), { runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, primary = action == Settings.ACTION_SETTINGS)
            }
        }
        if (shortcuts.isEmpty()) Label(stringResource(R.string.android_settings_none), Typography.small, c.textMuted)
    }
}

/** Los permisos de LiteDash en Ajustes de Android (para dar o quitar ubicación). */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Notas de una versión (sección del CHANGELOG) en renglones: títulos ("### Agregado") y viñetas sin formato. */
private fun releaseNotes(notes: String = BuildConfig.RELEASE_NOTES): List<String> {
    val items = mutableListOf<String>()
    for (raw in notes.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("###") -> items += line
            line.startsWith("- ") -> items += line.removePrefix("- ")
            items.isNotEmpty() && !items.last().startsWith("###") -> items[items.lastIndex] = items.last() + " " + line
        }
    }
    return items.map { it.replace("**", "") }
}

/** Lo básico de la pantalla: equipo, Android, resolución y memoria (sin etiquetas: se entienden solos). */
private fun screenLines(context: Context): List<String> {
    val m = context.resources.displayMetrics
    return listOf(
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · ${Build.HARDWARE}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}",
        "${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi · ${memoryMb(context)} MB",
    )
}

/** Cuánto se espera la confirmación de Android antes de ofrecer el instalador de siempre. */
private const val INSTALLER_WAIT_MS = 8_000L

/** Estados del botón de actualizaciones (solo versión de GitHub). */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Version) : UpdateState
    data class NeedsPermission(val v: Version) : UpdateState
    data object Error : UpdateState
}

@Composable
private fun Updates(a: Updater) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La confirmación de Android ("¿Instalar?") la abre esta pantalla, que está al frente.
    val confirm = Updater.confirm
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        Updater.confirm = null
        runCatching { context.startActivity(confirm) }
    }
    // Si al abrir ya se encontró una versión nueva, se muestra de una vez.
    var state by remember { mutableStateOf(a.found?.let { UpdateState.Available(it) } ?: UpdateState.Idle) }

    fun install(v: Version) {
        if (!a.canInstall()) {
            state = UpdateState.NeedsPermission(v)
            a.requestInstallPermission()
            return
        }
        // La descarga vive en el Updater: sigue aunque se cierre Ajustes.
        a.downloadAndInstall(v)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val p = a.progress) {
            is Updater.Progress.Downloading -> Label(stringResource(R.string.downloading, p.percent), Typography.body, c.accent)
            is Updater.Progress.Installing -> {
                Label(stringResource(R.string.installing), Typography.body, c.accent)
                // Si la confirmación de Android no aparece en unos segundos (o falla), el respaldo.
                var stuck by remember(p) { mutableStateOf(false) }
                LaunchedEffect(p) {
                    delay(INSTALLER_WAIT_MS)
                    stuck = true
                }
                if (stuck || Updater.installError != null) {
                    Button(stringResource(R.string.open_installer), { a.openWithInstaller(p.apk) }, primary = true)
                }
            }
            // Sin descarga en curso: buscar, la versión encontrada o el permiso de instalar.
            else -> when (val e = state) {
                UpdateState.Idle, UpdateState.UpToDate, UpdateState.Error -> {
                    Button(stringResource(R.string.check_updates), {
                        state = UpdateState.Checking
                        scope.launch {
                            state = runCatching { a.checkForUpdate() }
                                .fold({ v -> if (v == null) UpdateState.UpToDate else UpdateState.Available(v) }, { UpdateState.Error })
                        }
                    })
                    if (e == UpdateState.UpToDate) Label(stringResource(R.string.up_to_date), Typography.small, c.accent)
                }
                UpdateState.Checking -> Label(stringResource(R.string.checking), Typography.body, c.textMuted)
                is UpdateState.Available -> NewVersion(e.v) { install(e.v) }
                is UpdateState.NeedsPermission -> {
                    Label(stringResource(R.string.install_permission), Typography.small, c.textMuted)
                    Button(stringResource(R.string.already_allowed), { install(e.v) }, primary = true)
                }
            }
        }
        // Falló la búsqueda o la última descarga (cada una con su aviso: no es lo mismo no revisar que no bajar).
        if (a.progress == Updater.Progress.Failed) Label(stringResource(R.string.update_download_error), Typography.small, c.danger)
        else if (state == UpdateState.Error) Label(stringResource(R.string.update_error), Typography.small, c.danger)
        Updater.installError?.let { Label(stringResource(R.string.install_error, it), Typography.small, c.danger) }
    }
}

@Composable
private fun NewVersion(v: Version, onInstall: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.accent, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(stringResource(R.string.new_version, v.name), Typography.subtitle)
        // Las notas del Release como lista corta (sin los títulos de sección).
        val notes = releaseNotes(v.notes).filterNot { it.startsWith("###") }.take(6).joinToString("\n") { "• $it" }
        if (notes.isNotBlank()) Label(notes, Typography.small, c.textMuted, maxLines = 8)
        Button(stringResource(R.string.download_install, v.bytes / 1e6f), onInstall, primary = true)
    }
}

/**
 * Un módulo: ícono, nombre e interruptor (todo eso se toca para prender o apagar) y, prendido y
 * con [onOpen], una flecha redonda aparte que entra a sus ajustes. Apagado, en gris. [soon]: aún
 * no existe (interruptor apagado sin tocar y la etiqueta "Pronto").
 */
@Composable
private fun ModuleRow(
    icon: ImageVector,
    title: String,
    on: Boolean,
    onChange: ((Boolean) -> Unit)?,
    soon: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    val dim = if (on) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (onChange != null) Modifier.clickable { onChange(!on) } else Modifier)
                .padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, if (on) c.accent else c.textMuted.copy(alpha = dim), 28.dp)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(title, Typography.subtitle, if (on) c.text else c.textMuted.copy(alpha = dim), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (soon) Label(
                    stringResource(R.string.module_soon),
                    Typography.small.copy(fontWeight = FontWeight.SemiBold),
                    c.textMuted,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Switch(on, Modifier.graphicsLayer { alpha = if (soon) 0.45f else 1f })
        }
        // La flecha va aparte (con su línea): entrar no prende ni apaga nada.
        Box(Modifier.width(76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (on && onOpen != null) {
                Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight(0.5f).background(c.line))
                IconButton(Icons.chevron, onOpen, size = 48.dp, background = c.surfaceHigh, color = c.text)
            }
        }
    }
}
