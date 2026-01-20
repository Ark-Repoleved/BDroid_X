package com.example.bd2modmanager

import android.content.Intent
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

import com.example.bd2modmanager.gpu.AstcCompressorBridge

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(applicationContext)
        
        // Initialize GPU ASTC compression bridge
        AstcCompressorBridge.initialize(applicationContext)

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
                if (showInstallDialog) {
                    val installJobs by viewModel.installJobs.collectAsState()
                    val finalResult by viewModel.finalInstallResult.collectAsState()
                    ParallelInstallDialog(
                        installJobs = installJobs,
                        finalResult = finalResult,
                        onDismiss = { viewModel.closeInstallDialog() }
                    )
                }

                val uninstallState by viewModel.uninstallState.collectAsState()
                UninstallDialog(
                    state = uninstallState,
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
}
