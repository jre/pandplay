package net.joshe.pandplay.ui

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import net.joshe.pandplay.*
import net.joshe.pandplay.remote.JsonDataSource

class PrefsDownloadFragment : PreferenceFragmentCompat() {
    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private lateinit var stationsPref: MultiSelectListPreference
    private lateinit var prevFreqUnit: DownloadFreqUnit
    private var freqCat: PrefCat? = null
    private val rescheduleKeys = setOf(
        PrefKey.DL_FREQ_UNIT.key,
        PrefKey.DL_FREQ_COUNT.key,
        PrefKey.DL_REQUIRE_CHARGING.key,
        PrefKey.DL_REQUIRE_UNMETERED.key,
        PrefKey.DL_REQUIRE_IDLE.key)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prevFreqUnit = PrefKey.DL_FREQ_UNIT.getAnyWithDefault(requireContext()) as DownloadFreqUnit
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceManager.sharedPreferencesName = PrefGroup.DOWNLOADER.key
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefChanged)

        val genCat = getPrefCategory(screen, getString(R.string.pref_dl_cat_gen))
        stationsPref = getMultiPref(screen, genCat, PrefKey.DL_STATIONS,
            getString(R.string.pref_dl_stations),
            emptyArray(), emptyArray())
        stationsPref.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { pref ->
            getString(R.string.pref_dl_stations_summary, pref.values.size, JsonDataSource.serviceName)
        }
        val spacePref = getTextPref(screen, genCat, PrefKey.DL_SPACE_MB,
            getString(R.string.pref_dl_space),
            getString(R.string.pref_dl_space_desc),
            InputType.TYPE_CLASS_NUMBER)
        spacePref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            getString(R.string.pref_dl_space_summary, pref.text)
        }
        val batchPref = getTextPref(screen, genCat, PrefKey.DL_BATCH_SIZE,
            getString(R.string.pref_dl_batchSize),
            getString(R.string.pref_dl_batchSize_desc),
            InputType.TYPE_CLASS_NUMBER)
        batchPref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            getString(R.string.pref_dl_batchSize_summary, pref.text)
        }

        freqCat = getPrefCategory(screen, getString(R.string.pref_dl_cat_freq))
        val freqCountPref = getTextPref(screen, freqCat, PrefKey.DL_FREQ_COUNT,
            getString(R.string.pref_dl_freqCount),
            getString(R.string.pref_dl_freqCount_desc),
            InputType.TYPE_CLASS_NUMBER)
        freqCountPref.setOnPreferenceChangeListener { preference, newValue ->
            val unit = PrefKey.DL_FREQ_UNIT.getAnyWithDefault(requireContext()) as DownloadFreqUnit
            val origStr = (newValue as? String)
            val fixed = origStr?.toLong()?.coerceIn(unit.min, unit.max)?.toString()
            if (origStr != fixed)
                freqCountPref.text = fixed
            origStr == fixed
        }
        freqCountPref.summaryProvider = Preference.SummaryProvider<Preference> { null }
        val freqUnitPref = getMenuPref(screen, freqCat, PrefKey.DL_FREQ_UNIT,
            getString(R.string.pref_dl_freqUnit),
            DownloadFreqUnit.values().map{getString(it.displayId)}.toTypedArray(),
            DownloadFreqUnit.values().map{it.toString()}.toTypedArray())
        freqUnitPref.summaryProvider = Preference.SummaryProvider<Preference> { null }
        freqCat!!.summaryProvider = Preference.SummaryProvider<Preference> { pref ->
            Log.v("PREF", "updating section summary ${freqCountPref.text} ${freqUnitPref.entry}")
            getString(R.string.pref_dl_freq_summary, freqCountPref.text, freqUnitPref.entry)
        }
        prefChanged.onSharedPreferenceChanged(preferenceManager.sharedPreferences, PrefKey.DL_FREQ_UNIT.key)

        val reqCat = getPrefCategory(screen, getString(R.string.pref_dl_cat_reqs))
        getSwitchPref(screen, reqCat, PrefKey.DL_REQUIRE_CHARGING,
            getString(R.string.pref_dl_reqCharge),
            getString(R.string.pref_dl_reqCharge_on),
            getString(R.string.pref_dl_reqCharge_off))
        getSwitchPref(screen, reqCat, PrefKey.DL_REQUIRE_UNMETERED,
            getString(R.string.pref_dl_reqWifi),
            getString(R.string.pref_dl_reqWifi_on),
            getString(R.string.pref_dl_reqWifi_off))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            getSwitchPref(screen, reqCat, PrefKey.DL_REQUIRE_IDLE,
                getString(R.string.pref_dl_reqIdle),
                getString(R.string.pref_dl_reqIdle_on),
                getString(R.string.pref_dl_reqIdle_off))

        preferenceScreen = screen
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        libraryViewModel.stationsChanged.observe(viewLifecycleOwner) { stations ->
            Log.v("PREF", "stationsChanged to ${stations.map{it.stationName}.joinToString(",")}")
            stationsPref.entries = stations.map{it.stationName}.toTypedArray()
            stationsPref.entryValues = stations.map{it.stationId}.toTypedArray()
            PrefKey.DL_STATIONS.getStringSetWithDefault(requireContext())?.let { selected ->
                stationsPref.values = selected
                Log.v("PREF", "selected to ${selected.joinToString(",")}")
            }
        }
        return view
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefChanged)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefChanged)
    }

    private val prefChanged = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        Log.v("PREF", "pref changed: ${key}")
        if (key == PrefKey.DL_FREQ_COUNT.key)
            freqCat?.updateSummary()
        else if (key == PrefKey.DL_FREQ_UNIT.key) {
            val newUnit = PrefKey.DL_FREQ_UNIT.getAnyWithDefault(requireContext()) as DownloadFreqUnit
            val oldCount = PrefKey.DL_FREQ_COUNT.getLongWithDefault(requireContext())
            val interval = prevFreqUnit.frequencyToIntervalMinutes(oldCount)
            val newCount = newUnit.intervalMinutesToFrequency(interval)
            Log.v("PREF", "changing freq count ${oldCount}->${newCount} on unit change ${prevFreqUnit}->${newUnit}")
            findPreference<EditTextPreference>(PrefKey.DL_FREQ_COUNT.key)?.let { pref ->
                pref.text = newCount.toString()
            }
            prevFreqUnit = newUnit
            freqCat?.updateSummary()
        }
        if (key in rescheduleKeys)
            downloadSongsPeriodically(requireContext())
    }
}
