package com.vincentla.bbrec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.vincentla.bbrec.ui.CameraScreen
import com.vincentla.bbrec.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private var cameraGranted by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var permanentlyDenied by mutableStateOf(false)   // observable so the button label tracks denial transitions
    private var hasAsked = false       // gates permanent-denial detection; persisted across recreation
    private var requesting = false     // in-flight guard: ActivityResultLauncher allows only one active launch

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requesting = false
        hasAsked = true
        refreshState()
    }

    // Recompute all permission-derived state from the live system values. Drives recomposition
    // via the observable fields. `shouldShowRequestPermissionRationale` is false both before the
    // first ask AND after a permanent deny, so gate on hasAsked and only weigh still-missing perms.
    private fun refreshState() {
        cameraGranted = isGranted(Manifest.permission.CAMERA)
        micGranted = isGranted(Manifest.permission.RECORD_AUDIO)
        val missing = requiredPerms.filterNot { isGranted(it) }
        permanentlyDenied = hasAsked && missing.isNotEmpty() &&
            missing.all { !shouldShowRequestPermissionRationale(it) }
    }

    private fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        if (requesting) return       // ignore double-taps; a second launch while one is pending crashes
        requesting = true
        permLauncher.launch(requiredPerms)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hasAsked = savedInstanceState?.getBoolean(KEY_HAS_ASKED) ?: false
        refreshState()
        // Auto-ask once on the first creation only — not on config-change / process-death recreates
        // (those would re-fire the dialog or, for a denied user, silently re-deny).
        if (savedInstanceState == null && !(cameraGranted && micGranted)) requestPermissions()

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (cameraGranted && micGranted) {
                        CameraScreen()
                    } else {
                        PermissionScreen(
                            cameraGranted = cameraGranted,
                            micGranted = micGranted,
                            permanentlyDenied = permanentlyDenied,
                            onRequest = ::requestPermissions,
                            onOpenSettings = ::openAppSettings,
                        )
                    }
                }
            }
        }
    }

    // Re-check on return from system Settings (or any resume) so a grant made there takes effect.
    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HAS_ASKED, hasAsked)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }

    private companion object {
        const val KEY_HAS_ASKED = "has_asked"
    }
}
