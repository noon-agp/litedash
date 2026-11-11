package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSong
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.Song
import com.irvingguerra.litedash.feature.music.SourceKind

/** La música del celular (LiteDash Connect) entra a la pieza Música y sus botones regresan por el enlace. */
fun wirePhoneMusic(link: PhoneLink, music: MusicRepository) {
    link.onMusic = { song -> music.fromPhone(song?.toSong(), artComing = song != null && song.hasArt && song.art == null) }
    music.phoneControl = link::command
    // El enlace ya traía una canción antes de unirlos (LiteDash volvió al frente).
    link.song?.let { link.onMusic?.invoke(it) }
}

private fun PhoneSong.toSong() = Song(
    title = title,
    artist = artist,
    app = app,
    artwork = art,
    playing = playing,
    durationMs = durationMs,
    positionMs = positionMs,
    readAt = readAt,
    speed = speed,
    pkg = "",
    seekable = seekable,
    actions = emptyList(),
    source = SourceKind.BLUETOOTH,
    buffering = buffering,
    error = null,
    phone = true,
)
