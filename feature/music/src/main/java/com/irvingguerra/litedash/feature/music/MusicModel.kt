package com.irvingguerra.litedash.feature.music

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Immutable

/**
 * Fuentes de música, como en un estéreo: USB es siempre el reproductor propio de LiteDash;
 * Bluetooth y la radio FM son apps del sistema de la pantalla que el usuario enlaza en Ajustes
 * (los paquetes cambian con la marca: `com.microntek.bt`, `com.ts.radio`…). La radio también
 * puede ser la de internet de LiteDash.
 */
enum class SourceKind { USB, BLUETOOTH, RADIO }

/**
 * Cómo se escucha música en la pieza Música, uno solo a la vez (Ajustes → Apps → Música): los
 * archivos de una memoria con el reproductor de LiteDash, el celular por Bluetooth o una app.
 */
enum class MusicMode { FILES, BLUETOOTH, APP }

/** Botón propio de la app que suena (Spotify: me gusta, aleatorio, repetir…). */
@Immutable
data class MediaAction(val id: String, val name: String, val icon: Bitmap?)

@Immutable
data class Song(
    val title: String,
    val artist: String,
    val app: String,
    val artwork: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** Momento (reloj de arranque) en que se leyó [positionMs], para calcular el avance sin preguntar. */
    val readAt: Long,
    val speed: Float,
    val pkg: String,
    /** Si la app deja adelantar o regresar arrastrando la barra. */
    val seekable: Boolean,
    val actions: List<MediaAction>,
    /** Fuente de la pantalla a la que pertenece la app ([pkg]); null = una app normal. */
    val source: SourceKind?,
    /** Conectando o cargando (la radio tarda unos segundos). */
    val buffering: Boolean,
    /** Lo que la app dice que falló; null si nada. */
    val error: String?,
    /**
     * No viene de una sesión que obedezca (un aviso de la app o lo que LiteDash mandó): play y
     * pausa van como teclas del volante, explícitas, y el estado es el que LiteDash supone.
     */
    val blind: Boolean = false,
    /** Viene del celular por LiteDash Connect: los botones van por el enlace ([MusicRepository.phoneControl]). */
    val phone: Boolean = false,
) {
    fun currentPosition(): Long {
        if (!playing) return positionMs
        val progress = ((SystemClock.elapsedRealtime() - readAt) * speed).toLong()
        return (positionMs + progress).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }
}
