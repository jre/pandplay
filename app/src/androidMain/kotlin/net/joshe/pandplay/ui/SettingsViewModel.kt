package net.joshe.pandplay.ui

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.joshe.pandplay.LibraryRepository
import net.joshe.pandplay.R

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = LibraryRepository(application.applicationContext)

    private val _loginResult = MutableLiveData<Pair<Boolean, String>?>(null)
    val loginResult: LiveData<Pair<Boolean, String>?> = _loginResult

    private var _loginJob: Job? = null

    fun startLogin() {
        require(_loginJob == null)
        _loginJob = viewModelScope.launch {
            Log.v("SETTINGS", "starting login")
            _loginResult.postValue(repo.serviceLogin(getApplication()))
            _loginJob = null
        }
    }

    fun cancelLogin() {
        Log.v("SETTINGS", "canceling login job $_loginJob")
        _loginJob?.apply {
            cancel()
            _loginJob = null
        }
        _loginResult.postValue(null)
    }

    fun showLoginFailed(message: String) {
        val context: Context = getApplication()
        val prefix = context.getString(R.string.login_failed)
        val error = if (message.isEmpty()) prefix else "${prefix}: $message"
        Log.e("LOGIN", "failed to log in: $error")
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }
}
