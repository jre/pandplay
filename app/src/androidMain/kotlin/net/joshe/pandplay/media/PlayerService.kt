package net.joshe.pandplay.media

import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.future
import net.joshe.pandplay.CHANNEL_ID_PLAYER
import net.joshe.pandplay.LibraryRepository
import net.joshe.pandplay.NOTIFICATION_ID_PLAYING
import net.joshe.pandplay.PANDPLAY_CONST_ID
import net.joshe.pandplay.PrefKey
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.db.Station
import kotlin.math.min

private const val COMMAND_CUSTOM_STOP = "$PANDPLAY_CONST_ID.media.COMMAND_CUSTOM_STOP"
private const val KEY_INTERNAL_CONTROLLER_FLAG = "$PANDPLAY_CONST_ID.media.INTERNAL_CONTROLLER_FLAG"

@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {
    companion object {
        fun getConnectionHints() = Bundle().apply { putBoolean(KEY_INTERNAL_CONTROLLER_FLAG, true) }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var repo: LibraryRepository
    private var session: MediaLibrarySession? = null
    private lateinit var stations: Deferred<StateFlow<List<Station>>>
    private var selectedStation: Station? = null
    private var stationUpdater: Job? = null

    private val customStopCommand = SessionCommand(COMMAND_CUSTOM_STOP, Bundle.EMPTY)
    private val customStopButton = CommandButton.Builder(CommandButton.ICON_STOP)
        .setDisplayName("Stop")
        .setSessionCommand(customStopCommand)
        .setEnabled(true)
        .build()

    private val playerCommands = Player.Commands.Builder()
        .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        .add(Player.COMMAND_GET_AUDIO_ATTRIBUTES)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_DEVICE_VOLUME)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_GET_TEXT)
        .add(Player.COMMAND_GET_TIMELINE)
        .add(Player.COMMAND_GET_TRACKS)
        .add(Player.COMMAND_GET_VOLUME)
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_PREPARE)
        .add(Player.COMMAND_RELEASE)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SET_MEDIA_ITEM)
        .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        .add(Player.COMMAND_SET_VOLUME)
        .add(Player.COMMAND_STOP)
        .build()

    private val sessionCommands = SessionCommands.Builder()
        .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
        .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
        .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
        .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
        .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
        .build()

    private val notifySessionCommands = sessionCommands.buildUpon().add(customStopCommand).build()

    override fun onCreate() {
        Log.v("MEDIA", "created service $this")
        super.onCreate()

        repo = LibraryRepository(this)

        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID_PLAYER)
            .setNotificationId(NOTIFICATION_ID_PLAYING)
            .build())

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            // XXX is there an advantage to using a mediasource factory?
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // XXX player.setShuffleOrder()
        player.addListener(playerListener)
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.playWhenReady = true

        session = MediaLibrarySession.Builder(this, player, sessionCallbacks)
            .build()

        stations = scope.async { repo.loadStationsFlow().stateIn(scope) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        job.cancel()
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
        Log.v("MEDIA", "destroyed service $this")
    }

    // XXX should SESSION_EXTRAS_KEY_ACCOUNT_NAME be used somewhere?

    // XXX somewhere I should use METADATA_KEY_IS_EXPLICIT if possible

    private suspend fun getStation(stationId: CharSequence?)
            = stations.await().value.find { it.stationId == stationId }

    private suspend fun prepareStation(stationId: CharSequence)
    = getStation(stationId.ifEmpty { PrefKey.PLAY_CUR_STATION.getStringWithDefault(this) })?.let { station ->
        if (station == selectedStation)
            null
        else {
            stationUpdater?.cancel()
            repo.loadPlaylistMediaItems(station)
        }
    } ?: emptyList()

    private fun stationChanged(station: Station, currentSongIds: List<SongId>) {
        PrefKey.PLAY_CUR_STATION.saveAny(this, station.stationId)
        selectedStation = station
        session?.player?.playlistMetadata = station.toMediaItem().mediaMetadata

        stationUpdater?.cancel()
        stationUpdater = scope.launch {
            var prevSongIds = currentSongIds.toSet()
            repo.loadPlaylistFlow(station).collect { songIdList ->
                val songIdSet = songIdList.toSet()
                val addItems = (songIdSet - prevSongIds).mapNotNull { songId ->
                    repo.loadMediaItem(songId, station)
                }
                val delIds = (prevSongIds - songIdSet).map { it.songIdentity }.toSet()
                Log.v("MEDIA", "collected ${addItems.size} added and ${delIds.size} deleted songs from room flow")
                session?.player?.let { player ->
                    if (delIds.isNotEmpty())
                        for (idx in (player.mediaItemCount - 1).downTo(0))
                            if (player.getMediaItemAt(idx).mediaId in delIds)
                                player.removeMediaItem(idx)
                    if (addItems.isNotEmpty())
                        player.addMediaItems(addItems)
                    if (prevSongIds.isEmpty() && songIdSet.isNotEmpty())
                        player.prepare()
                    @Suppress("AssignedValueIsNeverRead") // I don't understand why kotlin warns here
                    prevSongIds = songIdSet
                }
            }
        }
    }

    private fun haveInternalConnections() = session?.connectedControllers?.any { controller ->
        controller.connectionHints.getBoolean(KEY_INTERNAL_CONTROLLER_FLAG)
    } ?: false

    private val sessionCallbacks = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo)
        = MediaSession.ConnectionResult.AcceptedResultBuilder(session).apply {
            setAvailablePlayerCommands(playerCommands)
            if (session.isMediaNotificationController(controller)) {
                setAvailableSessionCommands(notifySessionCommands)
                session.setMediaButtonPreferences(controller, listOf(customStopButton))
            } else
                setAvailableSessionCommands(sessionCommands)
        }.build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     customCommand: SessionCommand, args: Bundle)
        = when (customCommand.customAction) {
            COMMAND_CUSTOM_STOP -> {
                val haveInternal = haveInternalConnections()
                Log.v("MEDIA", "custom stop command haveInternal=$haveInternal")
                if (haveInternal)
                    session.player.stop()
                else
                    pauseAllPlayersAndStopSelf()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
        = scope.future {
            MediaSession.MediaItemsWithStartPosition(
                prepareStation(selectedStation?.stationId ?: ""),
                C.INDEX_UNSET, C.TIME_UNSET)
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                      params: LibraryParams?)
                = Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem(this@PlayerService), params))

        // https://stackoverflow.com/questions/57321200/how-to-update-default-error-text-in-media-browser-for-android-auto

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?)
        = scope.future {
            val items = when (parentId) {
                rootMediaId -> listOf(stationsListMediaItem(this@PlayerService))
                stationsListMediaId -> stations.await().value.map { it.toMediaItem() }
                else -> getStation(parentId)?.let { station ->
                        repo.loadPlaylistMediaItems(station)
                } ?: emptyList()
            }
            LibraryResult.ofItemList(items.subList(pageSize * page, min(items.size, pageSize * (page + 1))), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String)
        = scope.future {
            val item = when (mediaId) {
                rootMediaId -> rootMediaItem(this@PlayerService)
                stationsListMediaId -> stationsListMediaItem(this@PlayerService)
                else -> getStation(mediaId)?.toMediaItem()
                    ?: repo.loadMediaItem(SongId(mediaId), null)
                    ?: return@future LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE)
            }
             LibraryResult.ofItem(item, null)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: List<MediaItem>)
                = Futures.immediateFailedFuture<List<MediaItem>>(UnsupportedOperationException())

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
        = scope.future {
            for (item in mediaItems) {
                getStation(item.mediaId)?.let { station ->
                    return@future MediaSession.MediaItemsWithStartPosition(prepareStation(station.stationId),
                    C.INDEX_UNSET, C.TIME_UNSET)
                }
                getStation(item.mediaMetadata.station)?.let { station ->
                    val items = prepareStation(station.stationId)
                    val idx = items.indexOfFirst { it.mediaId == item.mediaId }
                    return@future MediaSession.MediaItemsWithStartPosition(items,
                        if (idx == -1) C.INDEX_UNSET else idx, C.TIME_UNSET)
                }
            }
            MediaSession.MediaItemsWithStartPosition(prepareStation(""),
                C.INDEX_UNSET, C.TIME_UNSET)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            session?.player?.currentMediaItem?.let { item ->
                Log.v("MEDIA", "listener onIsPlayingChanged isPlaying=${isPlaying} for ${item.mediaId} - ${item.mediaMetadata.title}")
                if (isPlaying)
                    selectedStation?.let { station ->
                        scope.launch { repo.saveSongPlayed(station, item.mediaId) }
                    }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val stationId = timeline.mediaItems().firstNotNullOfOrNull { item ->
                item.mediaMetadata.station?.ifEmpty { null }
            }
            if (stationId != null) scope.launch {
                val station = getStation(stationId)
                if (station != null && station != selectedStation)
                    stationChanged(station, timeline.mediaItems().map { SongId(it.mediaId) })
            }
        }
    }
}
