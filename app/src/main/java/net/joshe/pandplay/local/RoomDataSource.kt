package net.joshe.pandplay.local

import android.content.Context
import androidx.room.withTransaction
import net.joshe.pandplay.db.*

class RoomDataSource(context: Context) {
    private val stationsHash = "stationsList"
    private val db = MusicDB.getInstance(context)
    private val dao = db.getMusicDao()

    suspend fun loadStations() = dao.getStations()
    suspend fun loadStationToken(station: Station) = dao.getStationToken(station.stationId)
    suspend fun loadStationsHash() = dao.getHash(stationsHash)

    suspend fun updateStations(hash: String, stations: List<StationMD>) {
        db.withTransaction {
            dao.saveHash(SavedHash(stationsHash, hash))
            dao.insertStations(stations)
        }
    }

    suspend fun loadPlaylistIds(station: Station) = dao.getStationSongIds(station.stationId)
    fun loadPlaylistIdFlow(station: Station) = dao.getStationSongIdsFlow(station.stationId)
    suspend fun loadSong(songId: SongId) = dao.getSong(songId.songIdentity)

    suspend fun addSong(station: Station, album: AlbumMD, song: SongMD) : Pair<SongId,Long> {
        var now = 0L
        db.withTransaction {
            dao.insertAlbum(album)
            dao.insertSong(song)
            now = System.currentTimeMillis() / 1000
            dao.addStationSong(StationSongMap(
                stationId = station.stationId,
                songIdentity = song.songIdentity,
                added = now,
                lastListened = 0
            ))
        }
        return Pair(SongId(song.songIdentity), now)
    }

    suspend fun listenSong(station: Station, songId: SongId) {
        dao.listenStationSong(station.stationId, songId.songIdentity, System.currentTimeMillis() / 1000)
    }

    suspend fun removeSong(station: Station, songId: SongId) {
        db.withTransaction {
            dao.deleteStationSong(StationSongMap(station.stationId, songId.songIdentity, 0, 0))
            val songStations = dao.getSongStations(songId.songIdentity)
            if (songStations.isNotEmpty())
                return@withTransaction
            val albumId = dao.getSongAlbum(songId.songIdentity)
            dao.deleteSong(songId)
            songId.getAudioFile().delete()
            val albumSongs = dao.getAlbumSongs(albumId.albumIdentity)
            if (albumSongs.isNotEmpty())
                return@withTransaction
            dao.deleteAlbum(albumId)
            albumId.getArtFile().delete()
        }
    }

    suspend fun loadPlaylistListened(station: Station) = dao.getStationSongListened(station.stationId)
    suspend fun loadPlaylistAdded(station: Station) = dao.getStationSongAdded(station.stationId)
}
