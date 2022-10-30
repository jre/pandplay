package net.joshe.pandplay

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.db.StationMD
import net.joshe.pandplay.db.AlbumMD
import net.joshe.pandplay.db.SongMD
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.local.RoomDataSource
import net.joshe.pandplay.local.createArtDir
import net.joshe.pandplay.local.createAudioDir
import net.joshe.pandplay.local.getArtFile
import net.joshe.pandplay.local.getAudioFile
import net.joshe.pandplay.remote.JsonDataSource
import net.joshe.pandplay.remote.GetStationListResponse.Station as JsonStation
import net.joshe.pandplay.remote.GetPlaylistResponse.Song as JsonSong
import java.io.File

class LibraryRepository(context: Context) {
    private val remote = JsonDataSource()
    private val local = RoomDataSource(context)

    fun haveCompleteCredentials(context: Context) = remote.haveCompleteCredentials(context)
    fun haveValidCredentials(context: Context) = remote.haveValidCredentials(context)

    suspend fun serviceLogin(context: Context) = withContext(Dispatchers.IO) {
        remote.login(context)
    }

    suspend fun loadStations() = withContext(Dispatchers.IO) {
        local.loadStations()
    }

    suspend fun maybeReloadStations(context: Context) : List<Station>? = withContext(Dispatchers.IO) {
        val (result, msg) = remote.ensureLogin(context)
        // XXX
        if (!result)
            return@withContext null
        if (local.loadStationsHash() == remote.fetchStationListHash()) {
            Log.v("REPO", "station hash unchanged, not reloading")
            local.loadStations()
        } else {
            Log.v("REPO", "station hash changed, reloading station list")
            reloadStations(context)
        }
    }

    private suspend fun reloadStations(context: Context) : List<Station>? {
        val (result, msg) = remote.ensureLogin(context)
        // XXX
        if (!result)
            return null
        createArtDir()
        val (hash, remoteStations) = remote.fetchStationList()
        val localStations = remoteStations.map { remoteStation ->
            val localStation = remoteStation.toStationMD()
            val artFile = localStation.getArtFile()
            if (!artFile.exists()) {
                val tmp = File(artFile.parent, "tmp-${artFile.name}")
                remote.fetchMedia(remoteStation.artUrl, tmp)
                tmp.renameTo(artFile)
            }
            localStation
        }
        local.updateStations(hash, localStations)
        return local.loadStations()
    }

    fun loadPlaylistFlow(station: Station) : Flow<List<SongId>>
        = local.loadPlaylistIdFlow(station).distinctUntilChanged()

    suspend fun loadPlaylist(station: Station) = withContext(Dispatchers.IO) {
        local.loadPlaylistIds(station)
    }

    suspend fun loadMediaDescription(songId: SongId) : MediaDescriptionCompat = withContext(Dispatchers.IO) {
        val song = local.loadSong(songId)!!
        return@withContext MediaDescriptionCompat.Builder()
            .setMediaId(song.songIdentity)
            .setMediaUri(song.getAudioFile().toUri())
            .setTitle(song.songName)
            .setSubtitle(song.artistName)
            .setIconUri(song.getArtFile().toUri())
            .build()
    }

    fun getMediaDescription(item: com.google.android.exoplayer2.MediaItem) : MediaDescriptionCompat {
        return MediaDescriptionCompat.Builder()
            .setMediaId(item.mediaId)
            // XXX is the media uri actually needed?
            // .setMediaUri(getSongFile(item.mediaId).toUri())
            .setTitle(item.mediaMetadata.title)
            .setSubtitle(item.mediaMetadata.artist)
            .setIconUri(item.mediaMetadata.artworkUri)
            .build()
    }

    suspend fun loadMediaItem(songId: SongId) = withContext(Dispatchers.IO) {
        val song = local.loadSong(songId)!!
        return@withContext com.google.android.exoplayer2.MediaItem.Builder()
            .setMediaId(song.songIdentity)
            .setUri(song.getAudioFile().toUri())
            .setMediaMetadata(
                com.google.android.exoplayer2.MediaMetadata.Builder()
                    .setArtist(song.artistName)
                    .setAlbumTitle(song.albumName)
                    .setTitle(song.songName)
                    .setArtworkUri(song.getArtFile().toUri())
                    .build())
            .build()
    }

    suspend fun saveSongPlayed(station: Station, mediaId: String) = local.listenSong(station, SongId(mediaId))

    suspend fun loadSongsPlayed(station: Station) = local.loadPlaylistListened(station)

    suspend fun loadSongsAdded(station: Station) = local.loadPlaylistAdded(station)

    suspend fun removeSongFromStation(station: Station, songId: SongId) = local.removeSong(station, songId)

    suspend fun fetchRemoteSongs(context: Context, station: Station) : List<JsonSong>? {
        val (result, msg) = remote.ensureLogin(context)
        // XXX
        if (!result)
            return null
        val fetched = remote.fetchSongMetadata(local.loadStationToken(station)!!)
        Log.v("REPO", "fetched metadata from station ${station.stationId} - ${station.stationName}\n    fetched ${fetched.size} song mds (${fetched.joinToString(","){it.songIdentity}})" )
        return fetched
    }

    suspend fun downloadSong(context: Context, station: Station, remoteSong: JsonSong) : Pair<SongId,Long>? {
        val (result, msg) = remote.ensureLogin(context)
        // XXX
        if (!result)
            return null

        val localAlbum = remoteSong.toAlbumMD()
        val localSong = remoteSong.toSongMD()

        createArtDir()
        val artFile = localSong.getArtFile()
        if (remoteSong.albumArtUrl.isEmpty()) {
            Log.v("REPO", "skipping song with empty art url '${remoteSong.albumArtUrl}' '${localAlbum.albumArtUrl}'")
            return null
        }
        if (!artFile.exists()) {
            val tmp = File(artFile.parent, "tmp-${artFile.name}")
            Log.v("REPO", "fetching album art ${artFile} from ${remoteSong.albumArtUrl}")
            remote.fetchMedia(remoteSong.albumArtUrl, tmp)
            tmp.renameTo(artFile)
        }

        createAudioDir()
        val songFile = localSong.getAudioFile()
        if (!songFile.exists()) {
            val tmp = File(songFile.parent, "tmp-${songFile.name}")
            Log.v("REPO", "fetching audio file ${songFile} from ${remoteSong.additionalAudioUrl[0]}")
            remote.fetchMedia(remoteSong.additionalAudioUrl[0], tmp)
            tmp.renameTo(songFile)
        }

        return local.addSong(station, localAlbum, localSong)
    }
}

fun JsonStation.toStationMD() = StationMD(
    allowAddMusic = allowAddMusic,
    allowDelete = allowDelete,
    allowRename = allowRename,
    artUrl = Uri.parse(artUrl),
    isGenreStation = isGenreStation,
    isQuickMix = isQuickMix,
    stationDetailUrl = Uri.parse(stationDetailUrl),
    stationId = stationId,
    stationName = stationName,
    stationToken = stationToken,
)

fun JsonSong.toAlbumMD() = AlbumMD(
    albumArtUrl = Uri.parse(albumArtUrl),
    albumDetailUrl = Uri.parse(albumDetailUrl),
    albumExplorerUrl = Uri.parse(albumExplorerUrl),
    albumIdentity = albumIdentity,
    albumName = albumName,
    artistDetailUrl = Uri.parse(artistDetailUrl),
    artistExplorerUrl = Uri.parse(artistExplorerUrl),
    artistName = artistName
)

fun JsonSong.toSongMD() = SongMD(
    albumIdentity = albumIdentity,
    allowFeedback = allowFeedback,
    audioUrl = Uri.parse(additionalAudioUrl[0]),
    songDetailUrl = Uri.parse(songDetailUrl),
    songExplorerUrl = Uri.parse(songExplorerUrl),
    songIdentity = songIdentity,
    songName = songName,
    songRating = songRating.toInt(),
    trackGain = trackGain,
    trackToken = trackToken,
    userSeed = userSeed,
)
