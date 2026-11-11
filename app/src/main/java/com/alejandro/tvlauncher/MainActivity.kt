package com.alejandro.tvlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var settings: LauncherSettings

    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var homeSignal by mutableIntStateOf(0)
    private var settingsSignal by mutableIntStateOf(0)
    private var locationGranted by mutableStateOf(false)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    // Android only shows the Wi-Fi network name to apps that hold a location permission.
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importWallpaper(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LauncherSettings(this)

        // Edge-to-edge, system bars hidden (immersive)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        reloadApps()

        locationGranted = hasLocationPermission()
        if (!locationGranted && !settings.askedLocation) {
            settings.askedLocation = true
            requestLocation()
        }

        setContent {
            LauncherRoot(
                allApps = apps,
                settings = settings,
                homeSignal = homeSignal,
                settingsSignal = settingsSignal,
                locationGranted = locationGranted,
                onLaunch = ::launch,
                onOptions = ::openAppInfo,
                onPickWallpaper = ::pickWallpaper,
                onRequestLocation = ::requestLocation,
                onSystemIntent = ::openSystem,
            )
        }
    }

    // Pressing the Home button while we're already open → jump back to the first tile.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
    }

    // The remote's Menu / Settings key opens launcher settings.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            settingsSignal++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in system settings.
        locationGranted = hasLocationPermission()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        runCatching { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            .onFailure {
                // Some boxes have no permission dialog; send the user to this app's settings page instead.
                openOwnAppSettings()
            }
    }

    private fun reloadApps() {
        lifecycleScope.launch { apps = AppRepo.load(this@MainActivity) }
    }

    private fun launch(app: AppEntry) {
        runCatching { startActivity(app.launchIntent) }
            .onFailure { Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show() }
    }

    private fun openOwnAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openAppInfo(app: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openSystem(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "This box doesn't have that screen", Toast.LENGTH_SHORT).show() }
    }

    private fun pickWallpaper() {
        runCatching { pickImage.launch("image/*") }
            .onFailure {
                Toast.makeText(
                    this,
                    "No photo picker found on this box. Install a file manager app and try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    /** Copies the chosen photo into app storage (downscaled) and switches to it. */
    private fun importWallpaper(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / sample > 2400) sample *= 2

                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        ?: return@runCatching false
                    FileOutputStream(Wallpapers.customFile(this@MainActivity)).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                settings.wallpaper = Wallpapers.CUSTOM
                settings.wallpaperRev += 1
            } else {
                Toast.makeText(this@MainActivity, "Couldn't read that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
