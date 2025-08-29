package net.joshe.pandplay.local

import android.content.Context
import androidx.startup.Initializer
import net.joshe.pandplay.PublicFileProvider
import net.joshe.pandplay.db.Song
import net.joshe.pandplay.db.SongMD
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.db.AlbumId
import net.joshe.pandplay.db.StationMD
import net.joshe.pandplay.R
import java.io.File

class LocalPaths(context: Context) {
    val authority = context.getString(R.string.file_provider_authority)
    val paths = mapOf(
        TAG_ART to context.getExternalFilesDir("art")!!,
        TAG_SONG to context.getExternalFilesDir("songs")!!,
    )

    companion object {
        const val TAG_ART = "album-art"
        const val TAG_SONG = "songs"

        private lateinit var instance: LocalPaths
        lateinit var paths: Map<String,File>
        lateinit var authority: String

        fun getInstance(context: Context) = synchronized(this) {
            instance = LocalPaths(context)
            paths = instance.paths
            authority = instance.authority
            instance
        }

        fun pathFor(tag: String, pathname: String) = File(paths.getValue(tag), pathname)

        fun uriFor(tag: String, pathname: String) = PublicFileProvider.getUri(tag, pathname)
    }
}

class LocalPathsInitializer : Initializer<LocalPaths> {
    override fun create(context: Context) = LocalPaths.getInstance(context)
    override fun dependencies() = emptyList<Class<Initializer<*>>>()
}

fun createArtDir() = LocalPaths.paths.getValue(LocalPaths.TAG_ART).mkdirs()
fun createAudioDir() = LocalPaths.paths.getValue(LocalPaths.TAG_SONG).mkdirs()

fun StationMD.getArtFile() = LocalPaths.pathFor(LocalPaths.TAG_ART, "station-${stationId}.jpg")

fun Song.getArtUri() = LocalPaths.uriFor(LocalPaths.TAG_ART, "${albumIdentity}.jpg")
fun SongMD.getArtFile() = LocalPaths.pathFor(LocalPaths.TAG_ART, "${albumIdentity}.jpg")
fun AlbumId.getArtFile() = LocalPaths.pathFor(LocalPaths.TAG_ART, "${albumIdentity}.jpg")

fun Song.getAudioUri() = LocalPaths.uriFor(LocalPaths.TAG_SONG, "${songIdentity}.mp4")
fun SongMD.getAudioFile() = LocalPaths.pathFor(LocalPaths.TAG_SONG, "${songIdentity}.mp4")
fun SongId.getAudioFile() = LocalPaths.pathFor(LocalPaths.TAG_SONG, "${songIdentity}.mp4")
