package com.example.bd2modmanager

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.bd2modmanager.ui.dialogs.*
import com.example.bd2modmanager.ui.screens.ModScreen
import com.example.bd2modmanager.ui.theme.BD2ModManagerTheme
import com.example.bd2modmanager.ui.viewmodel.MainViewModel
import com.example.bd2modmanager.utils.SafManager
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, UI will update automatically via state
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(applicationContext)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            // 自動請求 Shizuku 權限
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (_: Exception) {
            // Shizuku not available
        }

        setContent {
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {
            // Shizuku not available
        }
    }
}

