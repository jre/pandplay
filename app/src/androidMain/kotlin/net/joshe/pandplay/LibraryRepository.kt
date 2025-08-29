package net.joshe.pandplay

import android.content.Context
import android.util.Log
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
import net.joshe.pandplay.media.toMediaItem
import net.joshe.pandplay.remote.JsonDataSource
import net.joshe.pandplay.remote.GetStationListResponse.Station as JsonStation
import net.joshe.pandplay.remote.GetPlaylistResponse.Song as JsonSong
import java.io.File
import androidx.core.net.toUri

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

    fun loadStationsFlow() = local.loadStationsFlow().distinctUntilChanged()

    suspend fun maybeReloadStations(context: Context) : List<Station>? = withContext(Dispatchers.IO) {
        val (result, _) = remote.ensureLogin(context)
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
        val (result, _) = remote.ensureLogin(context)
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

    suspend fun loadMediaItem(songId: SongId, station: Station?) = withContext(Dispatchers.IO) {
        local.loadSong(songId)?.toMediaItem(station)
    }

    suspend fun loadPlaylistMediaItems(station: Station) = withContext(Dispatchers.IO) {
        local.loadPlaylistIds(station).mapNotNull { local.loadSong(it)?.toMediaItem(station) }
    }

    suspend fun saveSongPlayed(station: Station, mediaId: String) = local.listenSong(station, SongId(mediaId))

    @Suppress("unused") // this will be used when proper playlist randomization is implemented
    suspend fun loadSongsPlayed(station: Station) = local.loadPlaylistListened(station)

    suspend fun loadSongsAdded(station: Station) = local.loadPlaylistAdded(station)

    suspend fun removeSongFromStation(station: Station, songId: SongId) = local.removeSong(station, songId)

    suspend fun fetchRemoteSongs(context: Context, station: Station) : List<JsonSong>? {
        val (result, _) = remote.ensureLogin(context)
        // XXX
        if (!result)
            return null
        val fetched = remote.fetchSongMetadata(local.loadStationToken(station)!!)
        Log.v("REPO", "fetched metadata from station ${station.stationId} - ${station.stationName}\n    fetched ${fetched.size} song mds (${fetched.joinToString(","){it.songIdentity}})" )
        return fetched
    }

    suspend fun downloadSong(context: Context, station: Station, remoteSong: JsonSong) : Pair<SongId,Long>? {
        val (result, _) = remote.ensureLogin(context)
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
            Log.v("REPO", "fetching album art $artFile from ${remoteSong.albumArtUrl}")
            remote.fetchMedia(remoteSong.albumArtUrl, tmp)
            tmp.renameTo(artFile)
        }

        createAudioDir()
        val songFile = localSong.getAudioFile()
        if (!songFile.exists()) {
            val tmp = File(songFile.parent, "tmp-${songFile.name}")
            Log.v("REPO", "fetching audio file $songFile from ${remoteSong.additionalAudioUrl[0]}")
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
    artUrl = artUrl.toUri(),
    isGenreStation = isGenreStation,
    isQuickMix = isQuickMix,
    stationDetailUrl = stationDetailUrl.toUri(),
    stationId = stationId,
    stationName = stationName,
    stationToken = stationToken,
)

fun JsonSong.toAlbumMD() = AlbumMD(
    albumArtUrl = albumArtUrl.toUri(),
    albumDetailUrl = albumDetailUrl.toUri(),
    albumExplorerUrl = albumExplorerUrl.toUri(),
    albumIdentity = albumIdentity,
    albumName = albumName,
    artistDetailUrl = artistDetailUrl.toUri(),
    artistExplorerUrl = artistExplorerUrl.toUri(),
    artistName = artistName
)

fun JsonSong.toSongMD() = SongMD(
    albumIdentity = albumIdentity,
    allowFeedback = allowFeedback,
    audioUrl = additionalAudioUrl[0].toUri(),
    songDetailUrl = songDetailUrl.toUri(),
    songExplorerUrl = songExplorerUrl.toUri(),
    songIdentity = songIdentity,
    songName = songName,
    songRating = songRating.toInt(),
    trackGain = trackGain,
    trackToken = trackToken,
    userSeed = userSeed,
)
