package net.joshe.pandplay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class PrefGroup(val key: String) {
    PLAYER("Player"),
    DOWNLOADER("Downloader"),
    WORKER("Worker"),
    JSON_API("JsonApi");

    fun getSharedPreferences(context: Context) : SharedPreferences
            = context.getSharedPreferences(key, Context.MODE_PRIVATE)
}

enum class PrefKey(val group: PrefGroup, val key: String, private val default: Any? = null) {
    PLAY_CUR_STATION(PrefGroup.PLAYER, "currentStation"),
    DL_STATIONS(PrefGroup.DOWNLOADER, "stations", emptySet<String>()),
    DL_SPACE_MB(PrefGroup.DOWNLOADER, "maxSpaceUsedMB", 100L),
    DL_FREQ_COUNT(PrefGroup.DOWNLOADER, "frequencyCount", 1L),
    DL_FREQ_UNIT(PrefGroup.DOWNLOADER, "frequencyUnit", DownloadFreqUnit.WEEKLY),
    DL_BATCH_SIZE(PrefGroup.DOWNLOADER, "batchSize", 20L),
    DL_REQUIRE_CHARGING(PrefGroup.DOWNLOADER, "requireCharging", true),
    DL_REQUIRE_UNMETERED(PrefGroup.DOWNLOADER, "requireUnmetered", true),
    DL_REQUIRE_IDLE(PrefGroup.DOWNLOADER, "requireIdle", true),
    WORK_LAST_PERIODIC(PrefGroup.WORKER, "lastPeriodicWork", 0L),
    J_API_HOST(PrefGroup.JSON_API, "rpcHost", "tuner.pandora.com"),
    J_API_PARTNER_USER(PrefGroup.JSON_API, "partnerUser", "android"),
    J_API_PARTNER_PASS(PrefGroup.JSON_API, "partnerPassword", "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7"),
    J_API_DEVICE(PrefGroup.JSON_API, "device", "android-generic"),
    J_API_OUT_KEY(PrefGroup.JSON_API, "encryptionKey", $$"6#26FRL$ZWD"),
    J_API_IN_KEY(PrefGroup.JSON_API, "decryptionKey", $$"R=U!LH$O2B#"),
    J_API_USER(PrefGroup.JSON_API, "username"),
    J_API_PASS(PrefGroup.JSON_API, "password"),
    J_API_LOGGED_IN(PrefGroup.JSON_API, "loggedInSuccessfully", false);

    fun getStringWithDefault(context: Context)
            = group.getSharedPreferences(context).getString(key, default as? String)
    fun getLongWithDefault(context: Context)
            = group.getSharedPreferences(context).getLong(key, default as Long)
    fun getBoolWithDefault(context: Context)
            = group.getSharedPreferences(context).getBoolean(key, default as Boolean)
    fun getStringSetWithDefault(context: Context)
            = group.getSharedPreferences(context).getStringSet(key, asStringSet(default))

    fun getAnyWithDefault(context: Context) : Any? {
        val pref = group.getSharedPreferences(context)
        return when(default) {
            is Boolean -> pref.getBoolean(key, default)
            is Int ->  pref.getLong(key, default.toLong())
            is Long ->  pref.getLong(key, default)
            is String? -> pref.getString(key, default)
            is Set<*> -> pref.getStringSet(key, asStringSet(default))
            is DownloadFreqUnit -> DownloadFreqUnit.fromString(pref.getString(key, null)) ?: default
            else -> null
        }
    }

    fun saveAny(context: Context, value: Any) {
        group.getSharedPreferences(context).edit(commit = true) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Short -> putLong(key, value.toLong())
                is Int -> putLong(key, value.toLong())
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, asStringSet(value))
            }
        }
    }
}

enum class DownloadFreqUnit(val displayId: Int, val min: Long, val max: Long, private val minutesPer: Long) {
    DAILY(R.string.enum_dlfreq_perday, 1, 24, 24 * 60),
    WEEKLY(R.string.enum_dlfreq_perweek, 1, 7 * 24, 7 * 24 * 60),
    MONTHLY(R.string.enum_dlfreq_permonth, 1, 30 * 24, 30 * 24 * 60),
    YEARLY(R.string.enum_dlfreq_peryear, 1, 365 * 24, 365 * 24 * 60);

    companion object {
        fun fromString(name: String?) = entries.find { it.name == name }
    }

    fun frequencyToIntervalMinutes(frequency: Long) = minutesPer / frequency.coerceIn(min, max)
    fun intervalMinutesToFrequency(interval: Long) = minutesPer / interval.coerceIn(1, minutesPer)
}

private fun asStringSet(value: Any?) = ((value ?: emptyList<String>()) as Collection<*>).map { it as String }.toSet()
