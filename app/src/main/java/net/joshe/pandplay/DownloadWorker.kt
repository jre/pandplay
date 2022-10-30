package net.joshe.pandplay

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import net.joshe.pandplay.db.SongId
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.local.getAudioFile
import net.joshe.pandplay.remote.JsonDataSource

private const val workNameNow = "downloadSongsImmediately"
private const val workNamePeriod = "downloadSongsPeriodically"
private const val inputDataManual = "inputDataManualWork"

private var haveChan = false

fun downloadSongsNow(context: Context) {
    if (!haveChan)
        haveChan = createNotificationChannel(context, CHANNEL_ID_DOWNLOAD,
            R.string.channel_download_name, R.string.channel_download_description)
    Log.v("WORKER", "starting worker to download songs now")
    val req = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(inputDataManual to true))
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workNameNow, ExistingWorkPolicy.REPLACE, req)
}

fun downloadSongsPeriodically(context: Context) {
    if (!haveChan)
        haveChan = createNotificationChannel(context, CHANNEL_ID_DOWNLOAD,
            R.string.channel_download_name, R.string.channel_download_description)
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(if (PrefKey.DL_REQUIRE_UNMETERED.getBoolWithDefault(context))
            NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(PrefKey.DL_REQUIRE_CHARGING.getBoolWithDefault(context))
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        constraints.setRequiresDeviceIdle(PrefKey.DL_REQUIRE_IDLE.getBoolWithDefault(context))

    val freqCount = PrefKey.DL_FREQ_COUNT.getLongWithDefault(context)
    val freqUnit = PrefKey.DL_FREQ_UNIT.getAnyWithDefault(context) as DownloadFreqUnit
    val intervalMins = freqUnit.frequencyToIntervalMinutes(freqCount)
    Log.v("WORKER", "scheduling worker to download songs ${freqCount}/${freqUnit} or every ${intervalMins} minutes")
    val req = PeriodicWorkRequestBuilder<DownloadWorker>(intervalMins, TimeUnit.MINUTES)
        .setInputData(workDataOf(inputDataManual to false))
        .setConstraints(constraints.build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(workNamePeriod, ExistingPeriodicWorkPolicy.REPLACE, req)
}

class DownloadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    private val repo = LibraryRepository(context)
    private var currentStation: Station? = null
    private var progressMax = 0
    private var progressCurrent = 0

    init {
        Log.v("WORKER", "initializing worker")
    }

    override suspend fun doWork(): Result {
        val isImmediate = inputData.getBoolean(inputDataManual, false)
        val stationIdSet = PrefKey.DL_STATIONS.getStringSetWithDefault(applicationContext)
        val songCount = PrefKey.DL_BATCH_SIZE.getLongWithDefault(applicationContext).toInt()
        val totalMB = PrefKey.DL_SPACE_MB.getLongWithDefault(applicationContext)
        val stations = repo.loadStations().filter { stationIdSet?.contains(it.stationId)?:false }
        if (stations.isEmpty() || songCount <= 0 || totalMB / stations.size <= 1) {
            Log.v("WORKER", "nothing to do ${stations.isEmpty()} ${songCount} ${if (stations.isNotEmpty()) totalMB / stations.size else null}")
            return Result.success()
        }

        return withContext(Dispatchers.IO) {
            if (!isImmediate && !shouldPeriodicWorkRun()) {
                Log.v("WORKER", "not running periodic work now")
                return@withContext Result.success()
            }
            Log.v("WORKER", "starting ${if (isImmediate) "immediate" else "periodic"} download of ${songCount} songs each for stations ${stationIdSet?.joinToString(",")} with max total size ${totalMB} MB")
            progressMax = stations.size * songCount
            progressCurrent = 0
            currentStation = null
            currentStation = null
            notify()
            val (loginSucceeded, loginMsg) =  repo.serviceLogin(applicationContext)
            if (!loginSucceeded) {
                Log.v("WORKER", "failed to log in: ${loginMsg}")
                return@withContext Result.failure()
            }
            for (st in stations)
                if (!downloadSimple(repo, st, songCount, totalMB / stations.size))
                    return@withContext Result.failure()
            Result.success()
        }
    }

    private suspend fun downloadSimple(repo: LibraryRepository, station: Station, count: Int, maxMB: Long) : Boolean {
        Log.v("WORKER", "downloading station ${station.stationName}")
        currentStation = station
        notify()

        val songsAdded = repo.loadSongsAdded(station).toMutableMap()
        val songSizes = songsAdded.keys.associateWith{it.getAudioFile().length()}.toMutableMap()
        var downloaded = 0
        while (downloaded < count) {
            val songMetaList = repo.fetchRemoteSongs(applicationContext, station)
                ?: return false  // XXX
            for (songMeta in songMetaList) {
                if (downloaded < count) {
                    pruneSongs(station, songsAdded, songSizes, maxMB)
                    Log.v("WORKER", "fetching song ${downloaded}/${count} for station ${station.stationId}: ${songMeta.songIdentity} - ${songMeta.songName}")
                    val (songId, added) = repo.downloadSong(applicationContext, station, songMeta)
                        ?: continue
                    songsAdded[songId] = added
                    songSizes[songId] = songId.getAudioFile().length()
                    progressCurrent++
                    downloaded++
                    notify()
                }
            }
        }
        return true
    }

    private suspend fun pruneSongs(station: Station, added: MutableMap<SongId, Long>, sizes: MutableMap<SongId, Long>, quotaMB: Long) {
        val chronological = added.keys.sortedBy{added[it]}.toMutableList()
        var totalBytes = sizes.values.sum()
        Log.v("WORKER", "")
        while (chronological.size > 0 && totalBytes > quotaMB * 1024 * 1024) {
            val dead = chronological.removeFirst()
            Log.v("WORKER", "pruning song ${dead.songIdentity} from station ${station.stationId}, added at ${added[dead]}")
            totalBytes -= sizes.getValue(dead)
            added.remove(dead)
            sizes.remove(dead)
            repo.removeSongFromStation(station, dead)
        }
    }

    private suspend fun shouldPeriodicWorkRun() : Boolean {
        val immediateRunning = withContext(Dispatchers.IO) {
            WorkManager.getInstance(applicationContext).getWorkInfos(
                WorkQuery.Builder
                    .fromUniqueWorkNames(listOf(workNameNow))
                    .addStates(listOf(WorkInfo.State.RUNNING))
                    .build()).get()
        }
        Log.v("WORKER", "found ${immediateRunning.size} immediate work jobs running")
        if (immediateRunning.isNotEmpty()) {
            return false
        }

        val lastWorkSecs = PrefKey.WORK_LAST_PERIODIC.getLongWithDefault(applicationContext)
        val curSecs = System.currentTimeMillis() / 1000
        val freqCount = PrefKey.DL_FREQ_COUNT.getLongWithDefault(applicationContext)
        val freqUnit = PrefKey.DL_FREQ_UNIT.getAnyWithDefault(applicationContext) as DownloadFreqUnit
        val intervalSecs = freqUnit.frequencyToIntervalMinutes(freqCount) * 60

        Log.v("WORKER", "last periodic work was ${(curSecs-lastWorkSecs)/60} minutes ago, last=${lastWorkSecs} cur=${curSecs} interval=${intervalSecs}(${intervalSecs/60} mins)")
        if (lastWorkSecs + intervalSecs > curSecs)
            return false
        PrefKey.WORK_LAST_PERIODIC.saveAny(applicationContext, curSecs)
        return true
    }

    private fun getNotification(stationName: String?) : Notification {
        val title = applicationContext.getString(R.string.notification_download_title)
        val msg = if (stationName is String)
            applicationContext.getString(R.string.notification_download_content, stationName)
        else
            applicationContext.getString(R.string.pref_loginButton_desc, JsonDataSource.serviceName)
        val cancelText = applicationContext.getString(R.string.notification_download_cancel)
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID_DOWNLOAD)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(msg)
            .setSmallIcon(R.drawable.ic_outline_cloud_download_24)
            .setOngoing(true)
            .setProgress(progressMax, progressCurrent, false)
            .addAction(android.R.drawable.ic_delete, cancelText, cancelIntent)
            .build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIFICATION_ID_DOWNLOADING,
                getNotification(currentStation?.stationName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(NOTIFICATION_ID_DOWNLOADING,
                getNotification(currentStation?.stationName))
    }

    private suspend fun notify() {
        try {
            // XXX can I check for a permission here? maybe only on S(?) or newer?
            setForeground(getForegroundInfo())
        } catch (e: Throwable) {
            Log.v("WORKER", "failed to set foreground info: ${e}")
        }
    }

    // XXX close network connection in onStopped
}
