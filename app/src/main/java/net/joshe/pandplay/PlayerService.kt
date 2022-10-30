package net.joshe.pandplay

import android.app.Notification
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueEditor
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.PlayerNotificationManager.NotificationListener
import kotlinx.coroutines.*
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.db.Station

const val PLAYER_SERVICE_STATIONS_ID = "StationListMediaId"

private var haveChan = false

class PlayerService : MediaBrowserServiceCompat() {
    private lateinit var rootMediaId: String
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var repo: LibraryRepository
    private lateinit var session: MediaSessionCompat
    private lateinit var player: ExoPlayer
    private var selectedStation: Station? = null
    private var stationUpdater: Job? = null
    private lateinit var notifier: PlayerNotificationManager

    override fun onCreate() {
        Log.v("MEDIA", "created service ${this}")
        super.onCreate()
        if (!haveChan)
            haveChan = createNotificationChannel(applicationContext, CHANNEL_ID_PLAYER,
                R.string.channel_player_name, R.string.channel_player_description)
        repo = LibraryRepository(applicationContext)
        rootMediaId = getString(R.string.app_name)

        session = MediaSessionCompat(this, this::class.simpleName!!)
        // XXX does exoplayer override all or some of this?
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS)
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_PREPARE or
                    PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SET_RATING)
            .build())
        sessionToken = session.sessionToken

        notifier = PlayerNotificationManager.Builder(this, NOTIFICATION_ID_PLAYING, CHANNEL_ID_PLAYER)
            .setNotificationListener(notificationListener)
            // XXX .setMediaDescriptionAdapter
            .build()
        with (notifier) {
            setMediaSessionToken(session.sessionToken)
            setUseNextAction(true)
            setUseNextActionInCompactView(true)
            setUsePreviousAction(true)
            setUsePreviousActionInCompactView(true)
            setUseStopAction(true)
            setUsePlayPauseActions(true)
            setUseFastForwardAction(false)
            setUseRewindAction(false)
            setUseChronometer(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            // XXX is there an advantage to using a mediasource factory?
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // XXX player.setShuffleOrder()
        player.addListener(playerListener)

        MediaSessionConnector(session).apply {
            setPlayer(player)
            setPlaybackPreparer(preparer)
            setQueueNavigator(Navigator(session, repo))
        }

        session.isActive = true
        scope.launch { prepareStation(null, false) }
    }

    private val notificationListener = object : NotificationListener {
        override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
            Log.v("MEDIA", "onNotificationPosted ongoing=${ongoing}")
            if (ongoing) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                    startService(Intent(applicationContext, this@PlayerService::class.java))
                else
                    startForegroundService(Intent(applicationContext, this@PlayerService::class.java))
                startForeground(notificationId, notification)
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            Log.v("MEDIA", "onNotificationCancelled dismissedByUser=${dismissedByUser}")
            stopForeground(false)
            stopSelf()
        }
    }

    private fun playbackStarted() {
        Log.v("MEDIA", "playbackStarted")
        notifier.setPlayer(player)
    }

    private fun playbackStopped() {
        Log.v("MEDIA", "playbackStopped")
        notifier.setPlayer(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        session.release()
        Log.v("MEDIA", "destroyed service ${this}")
    }

    // XXX should SESSION_EXTRAS_KEY_ACCOUNT_NAME be used somewhere?

    // XXX somewhere I should use METADATA_KEY_IS_EXPLICIT if possible

    suspend fun prepareStation(station: Station?, playWhenReady: Boolean) {
        val st = loadOrSaveCurrentStation(station)
        if (st == null) {
            Log.v("MEDIA", "prepareStation failed to get current station")
            return
        }
        Log.v("MEDIA", "prepareStation loading ${st.stationId} - ${st.stationName}")
        stationUpdater?.cancel()
        session.setQueueTitle(st.stationName)
        // XXX preserve isPlaying here
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.clearMediaItems()
        player.playWhenReady = playWhenReady
        // https://exoplayer.dev/doc/reference/com/google/android/exoplayer2/source/ShuffleOrder.html
        // player.setShuffleOrder()
        selectedStation = st
        stationUpdater = currentCoroutineContext().job
        var prevSongIds: Set<SongId> = emptySet()
        repo.loadPlaylistFlow(st).collect {songIdList ->
            val songIdSet = songIdList.toSet()
            val addItems = (songIdSet - prevSongIds).map { repo.loadMediaItem(it) }
            val delIds = (prevSongIds - songIdSet).map { it.songIdentity }.toSet()
            Log.v("MEDIA", "collected ${addItems.size} added and ${delIds.size} deleted songs from room flow")
            if (delIds.isNotEmpty())
                for (idx in (player.mediaItemCount - 1).downTo(0))
                    if (player.getMediaItemAt(idx).mediaId in delIds)
                        player.removeMediaItem(idx)
            if (addItems.isNotEmpty())
                player.addMediaItems(addItems)
            if (prevSongIds.isEmpty() && songIdSet.isNotEmpty())
                player.prepare()
            prevSongIds = songIdSet
        }
    }

    private suspend fun loadOrSaveCurrentStation(station: Station?) : Station? {
        val saved = PrefKey.PLAY_CUR_STATION.getStringWithDefault(applicationContext)
        Log.v("MEDIA", "loadOrSaveCurrentStation ${station?.stationId}(${station?.stationName}) against saved currentStation ${saved}")
        if (station == null)
            return repo.loadStations().find { saved == it.stationId }
        if (station.stationId != saved)
            PrefKey.PLAY_CUR_STATION.saveAny(applicationContext, station.stationId)
        return station
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.v("MEDIA", "onGetRoot from ${clientPackageName} with ${rootHints}")
        // https://developer.android.com/training/cars/media#build_hierarchy
        // XXX checkPermission() for something here?
        // XXX look for BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS here
        // XXX maybe use BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS here
        // XXX use BROWSER_SERVICE_EXTRAS_KEY_APPLICATION_PREFERENCES_USING_CAR_APP_LIBRARY_INTENT somehow
        return BrowserRoot(rootMediaId, null)
    }

    // https://stackoverflow.com/questions/57321200/how-to-update-default-error-text-in-media-browser-for-android-auto

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId == rootMediaId) {
            Log.v("MEDIA", "onLoadChildren root")
            result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder()
                .setMediaId(PLAYER_SERVICE_STATIONS_ID)
                .setTitle(getString(R.string.media_stations_title))
                .setSubtitle(getString(R.string.media_stations_subtitle))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)))
            return
        }

        Log.v("MEDIA", "onLoadChildren detaching")
        result.detach()
        scope.launch(Dispatchers.IO) {
            val stations = repo.loadStations()

            if (parentId == PLAYER_SERVICE_STATIONS_ID) {
                Log.v("MEDIA", "onLoadChildren stations with ${stations.size} stations loaded")
                result.sendResult(stations.map { station ->
                    MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(station.stationId)
                            .setTitle(station.stationName)
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                }.toMutableList())
                return@launch
            }

            val station = stations.find { parentId == it.stationId }
            if (station !is Station) {
                Log.v("MEDIA", "onLoadChildren for invalid station id: ${parentId}")
                result.sendResult(null)
                return@launch
            }

            result.sendResult(repo.loadPlaylist(station).map { mediaId ->
                Log.v("MEDIA", "loading metadata for media file ${mediaId}")
                MediaBrowserCompat.MediaItem(
                    repo.loadMediaDescription(mediaId),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            }.toMutableList())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            player.currentMediaItem?.let { mediaItem ->
                Log.v("MEDIA", "listener onIsPlayingChanged isPlaying=${isPlaying} for ${mediaItem.mediaId} - ${mediaItem.mediaMetadata.title}")
                if (isPlaying)
                    selectedStation?.let { st ->
                        scope.launch { repo.saveSongPlayed(st, mediaItem.mediaId) }
                    }
            }
        }

        override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            Log.v("MEDIA", "listener onMediaItemTransition ${reason} ${mediaItem?.mediaId}/${mediaItem?.mediaMetadata?.title}")
            if (mediaItem == null)
                playbackStopped()
            else
                playbackStarted()
        }
    }

    private val preparer = object : MediaSessionConnector.PlaybackPreparer {
        override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
            Log.v("MEDIA", "preparer onCommand ${command}")
            return false
        }

        override fun getSupportedPrepareActions(): Long
            = PlaybackStateCompat.ACTION_PREPARE or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID

        override fun onPrepare(playWhenReady: Boolean) {
            Log.v("MEDIA", "preparer onPrepare playWhenReady=${playWhenReady}")
            scope.launch { prepareStation(null, playWhenReady) }
        }

        override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
            Log.v("MEDIA", "preparer onPrepareFromMediaId ${mediaId}")
            scope.launch {
                val found = repo.loadStations().find { mediaId == it.stationId }
                if (found == null)
                // XXX support individual song ids here by adding them next in the queue and skipping to them
                    Log.v("MEDIA", "ignoring request to prepare unknown station id ${mediaId}")
                else
                    prepareStation(found, playWhenReady)
            }
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {}

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {}
    }

    class Navigator(session: MediaSessionCompat, private val repo: LibraryRepository) : TimelineQueueNavigator(session, Int.MAX_VALUE) {
        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
            val item = player.getMediaItemAt(windowIndex)
            return repo.getMediaDescription(item)
        }
    }
}
