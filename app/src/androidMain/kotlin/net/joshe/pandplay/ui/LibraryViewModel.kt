package net.joshe.pandplay.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import net.joshe.pandplay.*
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.media.PlayerService
import net.joshe.pandplay.media.getIndexOfMediaId
import net.joshe.pandplay.media.toMediaItem

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    init {
        Log.v("LIBRARY", "init library view model")
    }
    private val repo = LibraryRepository(application.applicationContext)

    private lateinit var token: SessionToken

    private val _stations = MutableLiveData<List<Station>>(emptyList())
    val stationsChanged : LiveData<List<Station>> = _stations
    private var currentStation: Station? = null
    init {
        viewModelScope.launch {
            val stations = repo.loadStations()
            Log.v("LIBRARY", "loaded stations ${stations.joinToString{it.stationId}}")
            currentStation = loadCurrentStation(stations)
            _stations.postValue(stations)
        }
    }

    private val _browser = MutableLiveData<MediaBrowser?>()
    val browserChanged: LiveData<MediaBrowser?> = _browser

    private val _metadata = MutableLiveData<MediaItem?>()
    val metadataChanged: LiveData<MediaItem?> = _metadata

    private val _playerState = MutableLiveData<@Player.State Int?>()
    val playerStateChanged: LiveData<@Player.State Int?> = _playerState

    private val _playlist = MutableLiveData(Timeline.EMPTY)
    val playlistChanged: LiveData<Timeline> = _playlist

    private val _playlistTitle = MutableLiveData("")
    val playlistTitleChanged: LiveData<String> = _playlistTitle

    private val browserListener = object : MediaBrowser.Listener {
        @UnstableApi
        override fun onError(controller: MediaController, sessionError: SessionError) {
            Log.v("BROWSER", "browser error: $sessionError")
        }

        override fun onDisconnected(controller: MediaController) {
            // XXX
            Log.v("BROWSER", "browser disconnected")
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = _metadata.postValue(item)

        override fun onPlaybackStateChanged(@Player.State state: Int) = _playerState.postValue(state)

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            Log.v("MODEL", "playlist title changed to ${mediaMetadata.title}")
            _playlistTitle.postValue((mediaMetadata.title ?: "").toString())
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            Log.v("MODEL", "playlist changed to ${timeline.periodCount} items")
            _playlist.postValue(timeline)
        }
    }

    fun skipToMediaId(mediaId: String) {
        Log.v("MODEL", "trying to skip to playlist item $mediaId")
        val idx = _playlist.value?.getIndexOfMediaId(mediaId)
        if (idx != null && idx != C.INDEX_UNSET) {
            Log.v("MODEL", "found playlist item $mediaId at timeline index $idx")
            _browser.value?.seekToDefaultPosition(idx)
        }
    }

    override fun onCleared() {
        Log.v("MODEL", "onCleared")
        super.onCleared()
        stopBrowser()
    }

    fun createBrowser(activity: Activity, component: ComponentName) {
        Log.v("BROWSER", "creating session token")
        token = SessionToken(activity, component)
    }

    suspend fun startBrowser(context: Context) {
        stopBrowser()
        Log.v("BROWSER", "creating browser")
        _browser.postValue(MediaBrowser.Builder(context, token)
            .setListener(browserListener)
            .setConnectionHints(PlayerService.getConnectionHints())
            .buildAsync()
            .await()
            .also { browser->
                browser.addListener(playerListener)
                _playlistTitle.postValue(browser.playlistMetadata.title?.toString() ?: "")
                _playlist.postValue(browser.currentTimeline)
                _metadata.postValue(browser.currentMediaItem)
            })
    }

    fun stopBrowser() {
        _browser.value?.let { b ->
            Log.v("BROWSER", "releasing browser")
            _browser.postValue(null)
            b.release()
        }
    }

    fun haveCompleteCredentials() = repo.haveCompleteCredentials(getApplication())
    fun haveValidCredentials() = repo.haveValidCredentials(getApplication())

    fun reloadStations() {
        viewModelScope.launch {
            repo.maybeReloadStations(getApplication())?.let { stations ->
                currentStation = loadCurrentStation(stations)
                _stations.postValue(stations)
            }
        }
    }

    fun getStationList() : List<Station> = _stations.value!!

    fun getCurrentStation() : Station? = currentStation

    private fun loadCurrentStation(stations: List<Station>) : Station? {
        val saved = PrefKey.PLAY_CUR_STATION.getStringWithDefault(getApplication())
        val station = stations.find { saved == it.stationId }
        Log.v("MODEL", "checked saved currentStation $saved against stationMap: $station")
        return station
    }

    fun changeStation(stationId: String) {
        if (stationId == currentStation?.stationId)
            return
        val st: Station? = getStationList().find { stationId == it.stationId }
        if (st is Station) {
            Log.v("MODEL", "trying to post station change to ${st.stationName}")
            PrefKey.PLAY_CUR_STATION.saveAny(getApplication(), st.stationId)
            currentStation = st
            _browser.value?.setMediaItem(st.toMediaItem())
        } else
            Log.v("MODEL", "can't change station to nonexistent $stationId")
    }

    fun downloadSongs(context: Context) {
        Log.v("MODEL", "downloading songs now")
        downloadSongsNow(context)
    }
}
