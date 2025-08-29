package net.joshe.pandplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import net.joshe.pandplay.R
import net.joshe.pandplay.db.Song
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.local.getArtUri
import net.joshe.pandplay.local.getAudioUri

const val rootMediaId = "RootMediaId"
const val stationsListMediaId = "StationListMediaId"

fun rootMediaItem(context: Context) = MediaItem.Builder()
    .setMediaId(rootMediaId)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(context.getString(R.string.app_name))
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .build())
    .build()

fun stationsListMediaItem(context: Context) = MediaItem.Builder()
    .setMediaId(stationsListMediaId)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(context.getString(R.string.media_stations_title))
        .setSubtitle(context.getString(R.string.media_stations_subtitle))
        .setIsBrowsable(true)
        .setIsPlayable(true)
        .build())
    .build()

fun Station.toMediaItem() = MediaItem.Builder()
    .setMediaId(stationId)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(stationName)
        .setIsBrowsable(true)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .build())
    .build()

fun Song.toMediaItem(station: Station?) = MediaItem.Builder()
    .setMediaId(songIdentity)
    .setUri(getAudioUri())
    .setMediaMetadata(MediaMetadata.Builder()
        .apply { if (station != null) setStation(station.stationId) }
        .setArtist(artistName)
        .setAlbumTitle(albumName)
        .setTitle(songName)
        .setSubtitle(artistName)
        .setArtworkUri(getArtUri())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build())
    .build()

fun Timeline.mediaItems() = Timeline.Window().let { win ->
    0.rangeUntil(windowCount).map { getWindow(it, win).mediaItem }
}

fun Timeline.getIndexOfMediaId(mediaId: CharSequence) = Timeline.Window().let { win ->
    0.rangeUntil(windowCount).firstOrNull { idx ->
        mediaId == getWindow(idx, win).mediaItem.mediaId
    } ?: C.INDEX_UNSET
}
