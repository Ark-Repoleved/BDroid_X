package com.example.bd2modmanager.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class SafManager {

    companion object {
        private const val BD2_SHARED_FOLDER_URI_STRING = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.neowiz.game.browndust2.android%2Ffiles%2FUnityCache%2FShared/document/primary%3AAndroid%2Fdata%2Fcom.neowiz.game.browndust2.android%2Ffiles%2FUnityCache%2FShared"

        fun createAccessIntent(): Intent {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            val uri = Uri.parse(BD2_SHARED_FOLDER_URI_STRING)
            intent.putExtra("android.provider.extra.INITIAL_URI", uri)
            return intent
        }
    }

    class PickDirectoryWithSpecialAccess : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent {
            return createAccessIntent()
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != Activity.RESULT_OK) {
                return null
            }
            // The contract's only job is to return the URI.
            // The caller is responsible for persisting the permission.
            return intent?.data
        }
    }
}
