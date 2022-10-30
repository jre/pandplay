package net.joshe.pandplay.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import net.joshe.pandplay.*
import net.joshe.pandplay.db.Station

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    init {
        Log.v("LIBRARY", "init library view model")
    }
    private val repo = LibraryRepository(application.applicationContext)

    private lateinit var browser: MediaBrowserCompat

    private val _stationsChanged = MutableLiveData<List<Station>>(emptyList())
    val stationsChanged : LiveData<List<Station>> = _stationsChanged
    private var currentStation: Station? = null
    init {
        viewModelScope.launch {
            val stations = repo.loadStations()
            Log.v("LIBRARY", "loaded stations ${stations.joinToString{it.stationId}}")
            currentStation = loadCurrentStation(stations)
            _stationsChanged.postValue(stations)
        }
    }

    private val _controllerConnected = MutableLiveData<MediaControllerCompat?>()
    val controllerConnected: LiveData<MediaControllerCompat?> = _controllerConnected

    private val _metadataChanged = MutableLiveData<MediaMetadataCompat?>()
    val metadataChanged: LiveData<MediaMetadataCompat?> = _metadataChanged

    private val _playbackChanged = MutableLiveData<PlaybackStateCompat?>()
    val playbackChanged: LiveData<PlaybackStateCompat?> = _playbackChanged

    private val _playlist = MutableLiveData<List<Pair<Long, MediaDescriptionCompat>>>(emptyList())
    val playlist: LiveData<List<Pair<Long, MediaDescriptionCompat>>> = _playlist

    private val _playlistTitle = MutableLiveData<String>("")
    val playlistTitle: LiveData<String> = _playlistTitle

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.v("BROWSER", "browser connected")
            val controller = MediaControllerCompat(application.applicationContext, browser.sessionToken)
            _controllerConnected.postValue(controller)
            controllerCallbacks.onQueueTitleChanged(controller.queueTitle)
            controllerCallbacks.onQueueChanged(controller.queue)
            controllerCallbacks.onMetadataChanged(controller.metadata)
            controllerCallbacks.onPlaybackStateChanged(controller.playbackState)
            controller.registerCallback(controllerCallbacks)
        }

        override fun onConnectionFailed() {
            Log.v("BROWSER", "browser failed to connect")
        }

        override fun onConnectionSuspended() {
            Log.v("BROWSER", "browser disconnected")
        }
    }

    private val controllerCallbacks = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = _metadataChanged.postValue(metadata)

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = _playbackChanged.postValue(state)

        override fun onQueueTitleChanged(title: CharSequence?) {
            Log.v("MODEL", "queue title changed to ${title}")
            _playlistTitle.postValue((title?:"").toString())
        }

        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
            Log.v("MODEL", "queue changed to ${queue?.size} items")
            _playlist.postValue(queue?.map { Pair(it.queueId, it.description) } ?: emptyList())
        }
    }

    fun skipToMediaId(mediaId: String) {
        Log.v("MODEL", "trying to skip to playlist item ${mediaId}")
        _playlist.value?.find{(_, desc) -> desc.mediaId == mediaId}?.let {(id, desc) ->
            Log.v("MODEL", "found playlist item ${mediaId} at queue id ${id} - ${desc.title}")
            _controllerConnected.value?.transportControls?.skipToQueueItem(id)
        }
    }

    override fun onCleared() {
        Log.v("MODEL", "onCleared")
        super.onCleared()
        _controllerConnected.value?.unregisterCallback(controllerCallbacks)
        stopBrowser()
    }

    fun createBrowser(activity: Activity, component: ComponentName) {
        Log.v("BROWSER", "creating browser")
        browser = MediaBrowserCompat(activity, component,  connectionCallbacks, null)
    }

    fun startBrowser() {
        Log.v("BROWSER", "trying to connect")
        browser.connect()
    }

    fun stopBrowser() {
        Log.v("BROWSER", "disconnecting")
        _controllerConnected.postValue(null)
        //controller?.let { controllerHandler(false, it) }
        browser.disconnect()
    }

    fun haveCompleteCredentials() = repo.haveCompleteCredentials(getApplication())
    fun haveValidCredentials() = repo.haveValidCredentials(getApplication())

    fun reloadStations() {
        viewModelScope.launch {
            repo.maybeReloadStations(getApplication())?.let { stations ->
                currentStation = loadCurrentStation(stations)
                _stationsChanged.postValue(stations)
            }
        }
    }

    fun getStationList() : List<Station> = _stationsChanged.value!!

    fun getCurrentStation() : Station? = currentStation

    private fun loadCurrentStation(stations: List<Station>) : Station? {
        val saved = PrefKey.PLAY_CUR_STATION.getStringWithDefault(getApplication())
        val station = stations.find { saved == it.stationId }
        Log.v("MODEL", "checked saved currentStation ${saved} against stationMap: ${station}")
        return station
    }

    fun changeStation(stationId: String) {
        if (stationId == currentStation?.stationId)
            return
        val st: Station? = getStationList().find { stationId == it.stationId }
        if (st is Station) {
            Log.v("MODEL", "trying to post station change to ${st.stationName}")
            PrefKey.PLAY_CUR_STATION.saveAny(getApplication(), st.stationId)
            currentStation?.stationId?.let { browser.unsubscribe(it) }
            currentStation = st
            _controllerConnected.value?.transportControls?.prepareFromMediaId(st.stationId, null)
        } else
            Log.v("MODEL", "can't change station to nonexistent ${stationId}")
    }

    fun downloadSongs(context: Context) {
        Log.v("MODEL", "downloading songs now")
        downloadSongsNow(context)
    }
}
