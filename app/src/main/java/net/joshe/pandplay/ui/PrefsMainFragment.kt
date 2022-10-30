package net.joshe.pandplay.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import net.joshe.pandplay.PrefKey
import net.joshe.pandplay.R
import net.joshe.pandplay.remote.JsonDataSource

class PrefsMainFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())

        // maybe use PreferenceHeaderFragmentCompat here?

        Preference(screen.context).apply {
            title = getString(R.string.pref_section_login, JsonDataSource.serviceName)
            summary = getString(R.string.pref_section_login_summary)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                findNavController().navigate(R.id.navigation_prefs_login)
                true
            }
            screen.addPreference(this)
        }

        Preference(screen.context).apply {
            title = getString(R.string.pref_section_download)
            summary = getString(R.string.pref_section_download_summary)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                findNavController().navigate(R.id.navigation_prefs_download)
                true
            }
            screen.addPreference(this)
        }

        preferenceScreen = screen
    }
}

fun getPrefCategory(preferenceScreen: PreferenceScreen, titleStr: String)
        = with(PrefCat(preferenceScreen.context)) {
    title = titleStr
    preferenceScreen.addPreference(this)
    this
}

class PrefCat(context: Context) : PreferenceCategory(context) {
    fun updateSummary() = notifyChanged()
}

fun getTextPref(preferenceScreen: PreferenceScreen, cat: PreferenceCategory?, prefKey: PrefKey,
                titleStr: String, descStr: String, inputType: Int? = null)
        = with(
    if (inputType is Int && (inputType and InputType.TYPE_CLASS_NUMBER) != 0)
        NumTextPref(preferenceScreen.context, prefKey)
    else
        EditTextPreference(preferenceScreen.context)
) {
    key = prefKey.key
    title = titleStr
    (cat ?: preferenceScreen).addPreference(this)
    dialogTitle = titleStr
    dialogMessage = descStr
    prefKey.getAnyWithDefault(preferenceScreen.context)?.let { text = it.toString() }
    if (inputType != null)
        setOnBindEditTextListener { it.inputType = inputType }
    summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
    this
}

class NumTextPref(context: Context, private val prefKey: PrefKey) : EditTextPreference(context) {
    override fun getPersistedString(default: String?) = prefKey.getLongWithDefault(context).toString()

    override fun persistString(value: String?): Boolean {
        value?.let { prefKey.saveAny(context, it.toInt()) }
        return true
    }
}

fun getSwitchPref(preferenceScreen: PreferenceScreen, cat: PreferenceCategory?, prefKey: PrefKey,
                  titleStr: String, descOnStr: String, descOffStr: String)
        = with(SwitchPreferenceCompat(preferenceScreen.context)) {
    key = prefKey.key
    title = titleStr
    (cat?:preferenceScreen).addPreference(this)
    isChecked = prefKey.getBoolWithDefault(preferenceScreen.context)
    summaryOn = descOnStr
    summaryOff = descOffStr
    this
}

fun getMenuPref(preferenceScreen: PreferenceScreen, cat: PreferenceCategory?, prefKey: PrefKey,
                titleStr: String, entryStrs: Array<CharSequence>, entryValueStrs: Array<CharSequence>)
        = with(DropDownPreference(preferenceScreen.context)) {
    key = prefKey.key
    title = titleStr
    (cat?:preferenceScreen).addPreference(this)
    entries = entryStrs
    entryValues = entryValueStrs
    prefKey.getAnyWithDefault(preferenceScreen.context)?.let { value = it.toString() }
    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    this
}

fun getMultiPref(preferenceScreen: PreferenceScreen, cat: PreferenceCategory?, prefKey: PrefKey,
                 titleStr: String, entryStrs: Array<CharSequence>, entryValueStrs: Array<CharSequence>)
        = with(MultiSelectListPreference(preferenceScreen.context)) {
    key = prefKey.key
    title = titleStr
    dialogTitle = titleStr
    (cat?:preferenceScreen).addPreference(this)
    entries = entryStrs
    entryValues = entryValueStrs
    prefKey.getStringSetWithDefault(preferenceScreen.context)?.let { values = it }
    this
}
