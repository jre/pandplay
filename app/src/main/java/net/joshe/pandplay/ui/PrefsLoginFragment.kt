package net.joshe.pandplay.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import net.joshe.pandplay.remote.JsonDataSource
import net.joshe.pandplay.PrefGroup
import net.joshe.pandplay.PrefKey
import net.joshe.pandplay.R

class PrefsLoginFragment : PreferenceFragmentCompat() {
    private val settingsViewModel: SettingsViewModel by activityViewModels()
    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val buttonKey = "loginButton"
    private lateinit var userPref: EditTextPreference
    private lateinit var passPref: EditTextPreference
    private lateinit var loginButton: LoginProgressDialog
    private var progressDialog: PreferenceDialogFragmentCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceManager.sharedPreferencesName = PrefGroup.JSON_API.key
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefChanged)

        val cat = getPrefCategory(screen,
            getString(R.string.pref_login_cat, JsonDataSource.serviceName))

        userPref = getTextPref(screen, cat, PrefKey.J_API_USER,
            getString(R.string.pref_username),
            getString(R.string.pref_username_desc, JsonDataSource.serviceName))

        passPref = getTextPref(screen, cat, PrefKey.J_API_PASS,
            getString(R.string.pref_password),
            getString(R.string.pref_password_desc, JsonDataSource.serviceName),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passPref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (TextUtils.isEmpty(pref.text)) "Not set" else "Set"
        }

        loginButton = LoginProgressDialog(screen, cat, buttonKey, getString(R.string.pref_loginButton),
            getString(R.string.pref_loginButton_desc, JsonDataSource.serviceName),
            R.layout.dialog_login_progress)
        updateLoginButton()

        preferenceScreen = screen
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        Log.v("LOGIN", "onCreateView for prefs fragment")
        settingsViewModel.loginResult.observe(viewLifecycleOwner) { result ->
            result?.let { (success, message) ->
                settingsViewModel.cancelLogin()
                if (success) {
                    libraryViewModel.reloadStations()
                    findNavController().navigate(R.id.navigation_player)
                } else
                    settingsViewModel.showLoginFailed(message)
            }
            progressDialog?.apply { dismiss() }
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
        Log.v("LOGIN", "pref changed: ${key}")
        if (key == PrefKey.J_API_USER.key || key == PrefKey.J_API_PASS.key) {
            PrefKey.J_API_LOGGED_IN.saveAny(requireContext(), false)
            updateLoginButton()
        }
    }

    private fun updateLoginButton() {
        loginButton.isEnabled = libraryViewModel.haveCompleteCredentials()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is LoginProgressDialog) {
            progressDialog = preference.displayDialog(this)
            settingsViewModel.startLogin()
        }
        else
            super.onDisplayPreferenceDialog(preference)
    }
}

class LoginProgressDialog(screen: PreferenceScreen, cat: PreferenceCategory?, keyStr: String, titleStr: String,
                          private val messageStr: String, layoutId: Int
) : DialogPreference(screen.context, null, androidx.preference.R.attr.dialogPreferenceStyle) {
    init {
        key = keyStr
        title = titleStr
        dialogTitle = titleStr
        positiveButtonText = null
        dialogLayoutResource = layoutId
        (cat?:screen).addPreference(this)
    }

    fun displayDialog(parent: Fragment) : PreferenceDialogFragmentCompat {
        val dialog = ProgressDialogFragment(key, messageStr)
        dialog.setTargetFragment(parent, 0)
        dialog.show(parent.parentFragmentManager, null)
        return dialog
    }
}

class ProgressDialogFragment(keyStr: String, private val messageStr: String) : PreferenceDialogFragmentCompat() {
    private val settingsViewModel: SettingsViewModel by activityViewModels()

    init {
        val b = Bundle(1)
        b.putString(ARG_KEY, keyStr)
        arguments = b
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        view.findViewById<TextView>(R.id.message).text = messageStr
        Log.v("PREF", "fake pref onBindDialogView")
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        Log.v("PREF", "fake pref dialog closed: positiveResult=${positiveResult}")
        settingsViewModel.cancelLogin()
    }
}
