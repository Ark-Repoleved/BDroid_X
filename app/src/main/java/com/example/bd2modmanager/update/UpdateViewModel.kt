package com.example.bd2modmanager.update

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    val repository = UpdateRepository(application.applicationContext)
    private val context: Context get() = getApplication<Application>().applicationContext

    private val _latestRelease = MutableStateFlow<UpdateRepository.Release?>(null)
    val latestRelease: StateFlow<UpdateRepository.Release?> = _latestRelease.asStateFlow()

    private val _updateApkReady = MutableStateFlow<String?>(null)
    val updateApkReady: StateFlow<String?> = _updateApkReady.asStateFlow()

    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    private val _betaChannel = MutableStateFlow(repository.isBetaChannelEnabled())
    val betaChannel: StateFlow<Boolean> = _betaChannel.asStateFlow()

    fun setBetaChannel(enabled: Boolean) {
        repository.setBetaChannelEnabled(enabled)
        _betaChannel.value = enabled
    }

    fun checkForUpdates(auto: Boolean = false) {
        if (_updateChecking.value) return
        _updateChecking.value = true
        viewModelScope.launch {
            try {
                val release = repository.fetchLatest()
                when {
                    release == null ->
                        if (!auto) toast("Update check failed: unable to reach GitHub.")
                    !repository.isNewerThanInstalled(release.version) ->
                        if (!auto) toast("Already on the latest version (${repository.installedVersion()}).")
                    auto && release.version == ignoredUpdateVersion() -> Unit
                    else -> _latestRelease.value = release
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (!auto) toast("Update check failed: ${e.message ?: "unknown error"}")
            } finally {
                _updateChecking.value = false
            }
        }
    }

    fun ignoreUpdateVersion(version: String) {
        _latestRelease.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_IGNORED, version).apply()
    }

    private fun ignoredUpdateVersion(): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IGNORED, "").orEmpty()

    fun dismissUpdate() {
        _latestRelease.value = null
    }

    fun dismissUpdateReady() {
        _updateApkReady.value = null
    }

    fun downloadUpdate(release: UpdateRepository.Release) {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { repository.isApkDownloaded(release) }) {
                _updateApkReady.value = withContext(Dispatchers.IO) { repository.downloadedApkFile()?.name }
                return@launch
            }
            val started = try {
                withContext(Dispatchers.IO) { repository.startApkDownload(release) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                toast("Download failed: ${e.message ?: "unknown error"}")
                return@launch
            }
            if (started != null) {
                toast("Downloading ${started.name} (see notification bar)...")
                viewModelScope.launch(Dispatchers.IO) {
                    while (_updateApkReady.value == null) {
                        delay(POLL_INTERVAL_MS)
                        val id = repository.currentDownloadId()
                        if (id != -1L && repository.isDownloadSuccessful(id)) {
                            repository.downloadedApkFile()?.let { f ->
                                _updateApkReady.value = f.name
                                break
                            }
                        }
                        if (!repository.hasActiveDownload() && !repository.isDownloadSuccessful(id)) {
                            break
                        }
                    }
                }
            } else if (withContext(Dispatchers.IO) { repository.hasActiveDownload() }) {
                toast("An update download is already in progress.")
            } else {
                toast("No matching APK found for this device ABI; please download from GitHub Releases.")
            }
        }
    }

    fun onUpdateDownloadComplete(id: Long) {
        viewModelScope.launch {
            if (id != withContext(Dispatchers.IO) { repository.currentDownloadId() }) return@launch
            val ok = withContext(Dispatchers.IO) { repository.isDownloadSuccessful(id) }
            if (ok) {
                _updateApkReady.value = withContext(Dispatchers.IO) { repository.downloadedApkFile()?.name }
                toast("Update package downloaded.")
            } else {
                toast("Update download failed. Please check network and retry.")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "UpdateViewModel"
        private const val PREFS = "update_prefs"
        private const val KEY_IGNORED = "ignored_version"
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
