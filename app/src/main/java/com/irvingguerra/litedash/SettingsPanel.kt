// Ajustes: secciones, menú, pestañas y la tarjeta compartida.
package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography

/** Secciones de Ajustes en el orden del menú: cada cosa vive en un solo lugar. */
enum class SettingsSection(val title: Int) {
    CAR(R.string.your_car), APPEARANCE(R.string.appearance), APPS(R.string.apps_section), WORKSHOP(R.string.workshop),
    TIRES(R.string.tires), WHEEL(R.string.wheel), MODULES(R.string.modules), ACCOUNT(R.string.account), SYSTEM(R.string.system),
}

/**
 * Las secciones de un módulo apagado no salen en el menú (Taller sin OBD, Llantas sin fuente);
 * la que ya está abierta sí (al prender Llantas se entra a elegir su fuente).
 */
private fun SettingsSection.shown(d: Dependencies, current: SettingsSection) = this == current || when (this) {
    SettingsSection.WORKSHOP -> d.obdModule
    SettingsSection.TIRES -> d.hasTpmsSource
    else -> true
}

private fun SettingsSection.icon() = when (this) {
    SettingsSection.APPEARANCE -> Icons.dashboard
    SettingsSection.CAR -> Icons.car
    SettingsSection.APPS -> Icons.apps
    SettingsSection.WHEEL -> Icons.wheel
    SettingsSection.WORKSHOP -> Icons.engine
    SettingsSection.TIRES -> Icons.tire
    SettingsSection.ACCOUNT -> Icons.account
    SettingsSection.MODULES -> Icons.settings
    SettingsSection.SYSTEM -> Icons.info
}

/**
 * Ajustes como en las pantallas de auto: menú de secciones a la izquierda y
 * tarjetas a la derecha. En pantallas angostas el menú queda solo con íconos.
 */
@Composable
fun SettingsPanel(
    d: Dependencies,
    revision: Int,
    close: () -> Unit,
    onEdit: () -> Unit,
    onTemplates: () -> Unit,
    start: SettingsSection = SettingsSection.CAR,
    /** Las llantas a pantalla completa (desde la tarjeta de Salud). */
    onTires: () -> Unit = {},
) {
    val c = LocalColors.current
    var section by rememberSaveable { mutableStateOf(start) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.background)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding(),
    ) {
        val narrow = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(if (narrow) 88.dp else 270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MenuEntry(Icons.back, stringResource(R.string.back_home), selected = false, narrow, close)
                Spacer(Modifier.height(10.dp))
                for (s in SettingsSection.entries) if (s.shown(d, section)) MenuEntry(s.icon(), stringResource(s.title), s == section, narrow) { section = s }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Label(stringResource(section.title), Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                when (section) {
                    SettingsSection.APPEARANCE -> Appearance(d, revision, onEdit, onTemplates, onTires = { section = SettingsSection.TIRES })
                    SettingsSection.CAR -> CarSettings(d)
                    SettingsSection.APPS -> AppsSettings(d)
                    SettingsSection.WHEEL -> {
                        SettingsCard(help = stringResource(R.string.wheel_help)) { WheelSettings(d.wheel, d.prefs) }
                        SettingsCard(stringResource(R.string.wheel_assigned)) { WheelAssigned(d.prefs) }
                    }
                    // Con el OBD apagado el escáner ni se crea.
                    SettingsSection.WORKSHOP -> if (d.obdModule) Workshop(d, onTires)
                    SettingsSection.TIRES -> TiresSettings(d)
                    SettingsSection.MODULES -> Modules(d, revision, open = { section = it })
                    SettingsSection.ACCOUNT -> AccountSettings(d.phone)
                    SettingsSection.SYSTEM -> SystemSettings(d)
                }
            }
        }
    }
}

/**
 * Pestañas de una sección con muchas partes (para no hacer tanto scroll). Devuelve la
 * elegida; se recuerda por sección ([key]) mientras LiteDash siga abierto (solo en memoria).
 */
@Composable
internal fun tabs(titles: List<Int>, key: String): Int {
    val selected = TabMemory[key] ?: 0
    // Mismo control que las opciones (estilo iOS): una pestaña a la vez.
    Segmented(titles.mapIndexed { i, t -> i to stringResource(t) }, selected, { TabMemory[key] = it }, Modifier.horizontalScroll(rememberScrollState()))
    return selected
}

/** Las pestañas de Ajustes → Sistema ([SystemTab]). */
internal const val SYSTEM_TABS = "system"

/** Ajustes → Sistema abre en Actualizaciones (desde el aviso de versión nueva). */
internal fun openUpdates() = selectTab(SYSTEM_TABS, SystemTab.UPDATES.ordinal)

/**
 * La pestaña elegida en cada sección (al cambiar de sección, o cerrar y abrir Ajustes, sigue ahí).
 * Solo en memoria: no se guarda, así que sus claves pueden cambiar sin migrar nada.
 */
private val TabMemory = mutableStateMapOf<String, Int>()

/** Cambia a otra pestaña de la sección [key] desde su contenido (p. ej. de Salud a Códigos). */
internal fun selectTab(key: String, index: Int) {
    TabMemory[key] = index
}

@Composable
private fun MenuEntry(icon: ImageVector, text: String, selected: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(if (selected) c.surfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (narrow) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, if (selected) c.accent else c.textMuted, 26.dp)
        if (!narrow) Label(text, Typography.subtitle, if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Tarjeta de Ajustes: título, controles y el ícono de la sección grande y tenue a la derecha. */
@Composable
internal fun SettingsCard(
    title: String? = null,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Label(title, Typography.subtitle, maxLines = 1)
            content()
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
    }
}

