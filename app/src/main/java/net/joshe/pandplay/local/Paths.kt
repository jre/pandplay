package net.joshe.pandplay.local

import android.content.Context
import androidx.startup.Initializer
import net.joshe.pandplay.db.Song
import net.joshe.pandplay.db.SongMD
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.db.AlbumId
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.db.StationMD
import java.io.File

class LocalPaths(context: Context) {
    val artDir = context.getExternalFilesDir("art")!!
    val songsDir = context.getExternalFilesDir("songs")!!

    companion object {
        lateinit var instance: LocalPaths
        fun getInstance(context: Context) = synchronized(this) {
            instance = LocalPaths(context)
            instance
        }
    }
}

class LocalPathsInitializer : Initializer<LocalPaths> {
    override fun create(context: Context) = LocalPaths.getInstance(context)
    override fun dependencies() = emptyList<Class<Initializer<*>>>()
}

fun createArtDir() = LocalPaths.instance.artDir.mkdirs()
fun createAudioDir() = LocalPaths.instance.songsDir.mkdirs()

fun Station.getArtFile() = File(LocalPaths.instance.artDir, "station-${stationId}.jpg")
fun StationMD.getArtFile() = File(LocalPaths.instance.artDir, "station-${stationId}.jpg")
fun Song.getArtFile() = File(LocalPaths.instance.artDir, "${albumIdentity}.jpg")
fun SongMD.getArtFile() = File(LocalPaths.instance.artDir, "${albumIdentity}.jpg")
fun AlbumId.getArtFile() = File(LocalPaths.instance.artDir, "${albumIdentity}.jpg")
fun Song.getAudioFile() = File(LocalPaths.instance.songsDir, "${songIdentity}.mp4")
fun SongMD.getAudioFile() = File(LocalPaths.instance.songsDir, "${songIdentity}.mp4")
fun SongId.getAudioFile() = File(LocalPaths.instance.songsDir, "${songIdentity}.mp4")
