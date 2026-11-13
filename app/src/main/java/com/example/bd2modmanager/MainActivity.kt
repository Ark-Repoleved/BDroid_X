package com.example.bd2modmanager

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.bd2modmanager.service.ShizukuManager
import com.example.bd2modmanager.ui.dialogs.*
import com.example.bd2modmanager.ui.screens.ModScreen
import com.example.bd2modmanager.ui.theme.BD2ModManagerTheme
import com.example.bd2modmanager.ui.viewmodel.MainViewModel
import com.example.bd2modmanager.update.UpdateViewModel
import com.example.bd2modmanager.utils.SafManager
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    private var shizukuConnectionToken: Any? = null

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == updateViewModel.repository.currentDownloadId()) {
                updateViewModel.onUpdateDownloadComplete(id)
            }
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                // Handled when user triggers move action
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        viewModel.initialize(applicationContext)
        shizukuConnectionToken = ShizukuManager.acquireConnection()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (_: Exception) {
            // Shizuku not available
        }

        setContent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notifLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = {}
                )
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            BD2ModManagerTheme {
                var uninstallConfirmationTarget by remember { mutableStateOf<String?>(null) }
                var showUnpackDialog by remember { mutableStateOf(false) }

                val modSourceDirLauncher = rememberLauncherForActivityResult(
                    contract = SafManager.PickDirectoryWithSpecialAccess(),
                    onResult = { uri ->
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.setModSourceDirectoryUri(this, uri)
                        }
                    }
                )

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModScreen(
                        viewModel = viewModel,
                        updateViewModel = updateViewModel,
                        onSelectModSource = { modSourceDirLauncher.launch(Unit) },
                        onUninstallRequest = { hash -> uninstallConfirmationTarget = hash },
                        onUnpackRequest = { showUnpackDialog = true },
                        onMergeRequest = { viewModel.initiateMerge(this) }
                    )
                }

                val showInstallDialog by viewModel.showInstallDialog.collectAsState()
                val moveState by viewModel.moveState.collectAsState()

                if (showInstallDialog) {
                    val installJobs by viewModel.installJobs.collectAsState()
                    val finalResult by viewModel.finalInstallResult.collectAsState()
                    ParallelInstallDialog(
                        installJobs = installJobs,
                        finalResult = finalResult,
                        moveState = moveState,
                        onMoveToGame = { viewModel.moveFilesToGame() },
                        onCancel = { viewModel.cancelInstall() },
                        onDismiss = { viewModel.closeInstallDialog() }
                    )
                }

                val uninstallState by viewModel.uninstallState.collectAsState()
                UninstallDialog(
                    state = uninstallState,
                    moveState = moveState,
                    onMoveToGame = { viewModel.moveFilesToGame() },
                    onDismiss = { viewModel.resetUninstallState() }
                )

                UninstallConfirmationDialog(
                    targetHash = uninstallConfirmationTarget,
                    onConfirm = { hash ->
                        viewModel.initiateUninstall(this, hash)
                        uninstallConfirmationTarget = null
                    },
                    onDismiss = {
                        uninstallConfirmationTarget = null
                    }
                )

                if (showUnpackDialog) {
                    val unpackState by viewModel.unpackState.collectAsState()
                    val unpackInputFile by viewModel.unpackInputFile.collectAsState()
                    UnpackDialog(
                        unpackState = unpackState,
                        inputFile = unpackInputFile,
                        onSetInputFile = { viewModel.setUnpackInputFile(it) },
                        onInitiateUnpack = { viewModel.initiateUnpack(this) },
                        onResetState = { viewModel.resetUnpackState() },
                        onDismiss = { showUnpackDialog = false }
                    )
                }

                val showMergeDialog by viewModel.showMergeDialog.collectAsState()
                if (showMergeDialog) {
                    val mergeState by viewModel.mergeState.collectAsState()
                    MergeSpineDialog(
                        state = mergeState,
                        onDismiss = { viewModel.resetMergeState() }
                    )
                }

                val staticImages by viewModel.staticPreviewImages.collectAsState()
                ImageViewerDialog(
                    imagePaths = staticImages,
                    onDismiss = { viewModel.dismissStaticPreview() }
                )

                val latestRelease by updateViewModel.latestRelease.collectAsState()
                UpdateDialog(
                    release = latestRelease,
                    onDownload = { release ->
                        updateViewModel.downloadUpdate(release)
                        updateViewModel.dismissUpdate()
                    },
                    onIgnore = { version -> updateViewModel.ignoreUpdateVersion(version) },
                    onDismiss = { updateViewModel.dismissUpdate() }
                )

                val updateApkReady by updateViewModel.updateApkReady.collectAsState()
                UpdateReadyDialog(
                    apkName = updateApkReady,
                    onInstall = { installUpdateApk() },
                    onDismiss = { updateViewModel.dismissUpdateReady() }
                )
            }
        }
    }

    private fun installUpdateApk() {
        val file = updateViewModel.repository.downloadedApkFile() ?: run {
            Toast.makeText(this, "Update APK not found. Please check for updates again.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Please allow BDroid X to install unknown apps, then tap Install again.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(this, "Cannot launch installer: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {
            // Shizuku not available
        }
        shizukuConnectionToken?.let { ShizukuManager.releaseConnection(it) }
        shizukuConnectionToken = null
    }
}
