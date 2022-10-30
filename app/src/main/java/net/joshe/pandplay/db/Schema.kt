package net.joshe.pandplay.db

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.Executors

@Database(
    entities = [SavedHash::class, StationMD::class, AlbumMD::class, SongMD::class, StationSongMap::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MusicDB : RoomDatabase() {
    abstract fun getMusicDao(): MusicDao

    companion object {
        private var instance: MusicDB? = null
        fun getInstance(context: Context): MusicDB {
            if (instance == null)
                synchronized(this) {
                    if (instance == null)
                        instance = Room.databaseBuilder(context, MusicDB::class.java, "musicdb")
                            .setQueryCallback({ sqlQuery, bindArgs ->
                                Log.v("QUERY", "\"${sqlQuery}\" with ${bindArgs}")
                            }, Executors.newSingleThreadExecutor())
                            .build()
                }
            return instance!!
        }
    }
}

@Entity(tableName = "hashes")
data class SavedHash(
    @PrimaryKey val name: String,
    val hash: String,
)

@Entity(tableName = "stations")
data class StationMD(
    val allowAddMusic: Boolean,
    val allowDelete: Boolean,
    val allowRename: Boolean,
    val artUrl: Uri,
    val isGenreStation: Boolean,
    val isQuickMix: Boolean,
    val stationDetailUrl: Uri,
    @PrimaryKey val stationId: String,
    val stationName: String,
    val stationToken: String,
)

@Entity(tableName = "albums")
data class AlbumMD(
    val albumArtUrl: Uri,
    val albumDetailUrl: Uri,
    val albumExplorerUrl: Uri,
    @PrimaryKey val albumIdentity: String,
    val albumName: String,
    val artistDetailUrl: Uri,
    val artistExplorerUrl: Uri,
    val artistName: String,
)

@Entity(tableName = "songs", foreignKeys = [ForeignKey(
    entity = AlbumMD::class,
    parentColumns = ["albumIdentity"],
    childColumns = ["albumIdentity"],
)])
data class SongMD(
    @ColumnInfo(index = true) val albumIdentity: String,
    val allowFeedback: Boolean,
    val audioUrl: Uri,
    val songDetailUrl: Uri,
    val songExplorerUrl: Uri,
    @PrimaryKey val songIdentity: String,
    val songName: String,
    val songRating: Int,
    val trackGain: String,
    val trackToken: String,
    val userSeed: String,
)

@Entity(
    tableName = "station_songs",
    primaryKeys = ["stationId", "songIdentity"],
    foreignKeys = [
        ForeignKey(entity = StationMD::class, parentColumns = ["stationId"], childColumns = ["stationId"]),
        ForeignKey(entity = SongMD::class, parentColumns = ["songIdentity"], childColumns = ["songIdentity"])],
    // XXX is this needed or is the warning spurious?
    indices = [Index(value = ["songIdentity"], unique = true)]
)
data class StationSongMap(
    val stationId: String,
    val songIdentity: String,
    val added: Long,
    val lastListened: Long,
)

@Dao
interface MusicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHash(hash: SavedHash)

    @Query("SELECT hash FROM hashes WHERE name = :name")
    suspend fun getHash(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationMD>)

    @Query("SELECT isGenreStation, isQuickMix, stationId, stationName FROM stations")
    suspend fun getStations(): List<Station>

    @Query("SELECT stationId, stationToken FROM stations WHERE stationId = :id")
    suspend fun getStationToken(id: String): StationToken?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumMD)

    @Delete(entity = AlbumMD::class)
    suspend fun deleteAlbum(albumId: AlbumId)

    @Query("SELECT songIdentity FROM songs WHERE albumIdentity = :id")
    suspend fun getAlbumSongs(id: String): List<SongId>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongMD)

    @Delete(entity = SongMD::class)
    suspend fun deleteSong(songId: SongId)

    @Query("SELECT albumIdentity FROM songs WHERE songIdentity = :id")
    suspend fun getSongAlbum(id: String): AlbumId

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addStationSong(stationSong: StationSongMap)

    @Delete(entity = StationSongMap::class)
    suspend fun deleteStationSong(stationSong: StationSongMap)

    @Query("SELECT stationId FROM station_songs WHERE songIdentity = :id")
    suspend fun getSongStations(id: String): List<StationId>

    @Query("UPDATE station_songs SET lastListened = :secs " +
           "WHERE stationId = :stationId AND songIdentity = :songIdentity")
    suspend fun listenStationSong(stationId: String, songIdentity: String, secs: Long)

    @Query("SELECT a.albumIdentity, a.albumName, a.artistName, s.songIdentity, s.songName " +
           "FROM albums a, songs s WHERE s.albumIdentity = a.albumIdentity AND s.songIdentity = :id")
    suspend fun getSong(id: String): Song?

    @Query("SELECT songIdentity FROM station_songs WHERE stationId = :id")
    suspend fun getStationSongIds(id: String): List<SongId>

    @Query("SELECT songIdentity FROM station_songs WHERE stationId = :id")
    fun getStationSongIdsFlow(id: String): Flow<List<SongId>>

    @MapInfo(keyColumn = "songIdentity", valueColumn = "lastListened")
    @Query("SELECT songIdentity, lastListened FROM station_songs WHERE stationId = :id")
    suspend fun getStationSongListened(id: String): Map<SongId, Long>

    @MapInfo(keyColumn = "songIdentity", valueColumn = "added")
    @Query("SELECT songIdentity, added FROM station_songs WHERE stationId = :id")
    suspend fun getStationSongAdded(id: String): Map<SongId, Long>
}

data class StationToken(val stationId: String, val stationToken: String)

data class Station(
    val isGenreStation: Boolean,
    val isQuickMix: Boolean,
    val stationId: String,
    val stationName: String,
)

data class StationId(val stationId: String)

data class AlbumId(val albumIdentity: String)

data class SongId(val songIdentity: String)

data class Song(
    val albumIdentity: String,
    val albumName: String,
    val artistName: String,
    val songIdentity: String,
    val songName: String,
)

private class Converters {
    @TypeConverter fun fromUri(uri: Uri) = uri.toString()
    @TypeConverter fun toUri(uriString: String) : Uri = Uri.parse(uriString)
}
