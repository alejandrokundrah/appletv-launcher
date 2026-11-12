package com.alejandro.tvlauncher

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.core.os.ConfigurationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var settings: LauncherSettings

    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var homeSignal by mutableIntStateOf(0)
    private var settingsSignal by mutableIntStateOf(0)
    private var locationGranted by mutableStateOf(false)
    private var tvListingsGranted by mutableStateOf(false)
    private var homeStatus by mutableStateOf("")
    private var isDefaultHome by mutableStateOf(false)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    // Android only shows the Wi-Fi network name to apps that hold a location permission.
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        // Ask for the next permission only now, so two dialogs never fight for the screen.
        maybeAskTvListings()
    }

    // Lets the launcher read "Continue watching" / recommended titles that apps publish to Android TV.
    private val askTvListings = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tvListingsGranted = granted
    }

    // Result of the system "set as home app" dialog (Android 10+).
    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeStatus()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importWallpaper(uri)
    }

    /**
     * Applies the language picked in the launcher's own settings ("" = follow the system), so the
     * launcher can be French even when the box itself is set to English.
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
            .getString("language", "") ?: ""
        val config = Configuration(newBase.resources.configuration)
        if (lang.isEmpty()) {
            // Undo any earlier override in this process so dates etc. follow the system again.
            ConfigurationCompat.getLocales(config).get(0)?.let { Locale.setDefault(it) }
            super.attachBaseContext(newBase)
            return
        }
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LauncherSettings(this)

        // After a language change the activity restarts; come back to the settings screen.
        val prefs = getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().putBoolean("reopen_settings", false).apply()
            settingsSignal = 1
        }

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

        refreshHomeStatus()
        locationGranted = hasLocationPermission()
        tvListingsGranted = hasTvListingsPermission()
        if (!locationGranted && !settings.askedLocation) {
            settings.askedLocation = true
            requestLocation() // its result callback asks for the TV-listings permission next
        } else {
            maybeAskTvListings()
        }

        setContent {
            LauncherRoot(
                allApps = apps,
                settings = settings,
                homeSignal = homeSignal,
                settingsSignal = settingsSignal,
                locationGranted = locationGranted,
                tvListingsGranted = tvListingsGranted,
                homeStatus = homeStatus,
                onMakeDefault = ::makeDefaultHome,
                onLaunch = ::launch,
                onLaunchContent = ::launchContent,
                onOptions = ::openAppInfo,
                onPickWallpaper = ::pickWallpaper,
                onRequestLocation = ::requestLocation,
                onRequestTvListings = ::requestTvListings,
                onSystemIntent = ::openSystem,
                onLanguageChanged = ::languageChanged,
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
        tvListingsGranted = hasTvListingsPermission()
        refreshHomeStatus()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /** Asks Android which app the Home button currently opens, so settings can show it. */
    private fun refreshHomeStatus() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        isDefaultHome = pkg == packageName
        homeStatus = when {
            pkg == null -> getString(R.string.home_unknown)
            pkg == packageName -> getString(R.string.home_this)
            pkg == "android" -> getString(R.string.home_not_set)
            else -> {
                val label = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
                if (label != null && label != pkg) "$label ($pkg)" else pkg
            }
        }
    }

    /** Asks Android to make this launcher the Home app (the official way on Android 10+). */
    private fun makeDefaultHome() {
        if (isDefaultHome) {
            Toast.makeText(this, getString(R.string.toast_already_default), Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                val launched = runCatching { homeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME)) }.isSuccess
                if (launched) return
            }
        }
        // Older Android, or a box without that dialog: the system's own home-app picker.
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) {
            Toast.makeText(
                this,
                getString(R.string.toast_home_howto, getString(R.string.app_name)),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** The language is applied when the activity is created, so restart it and reopen settings. */
    private fun languageChanged() {
        getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("reopen_settings", true).apply()
        recreate()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasTvListingsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, "android.permission.READ_TV_LISTINGS") ==
            PackageManager.PERMISSION_GRANTED

    /** First launch only: ask once, automatically. Settings has a row to ask again. */
    private fun maybeAskTvListings() {
        if (!tvListingsGranted && !settings.askedTvListings) {
            settings.askedTvListings = true
            requestTvListings()
        }
    }

    private fun requestTvListings() {
        runCatching { askTvListings.launch("android.permission.READ_TV_LISTINGS") }
            .onFailure { openOwnAppSettings() }
    }

    /** Opens a title from the content row inside its app, falling back to just opening the app. */
    private fun launchContent(item: ContentItem) {
        val opened = runCatching {
            val uri = item.intentUri ?: error("no intent")
            val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
        }.isSuccess
        if (!opened) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(item.packageName)
                ?: packageManager.getLaunchIntentForPackage(item.packageName)
            runCatching { startActivity(fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(this, getString(R.string.toast_cant_open, item.title), Toast.LENGTH_SHORT).show() }
        }
    }

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
            .onFailure { Toast.makeText(this, getString(R.string.toast_cant_open, app.label), Toast.LENGTH_SHORT).show() }
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
            .onFailure { Toast.makeText(this, getString(R.string.toast_no_screen), Toast.LENGTH_SHORT).show() }
    }

    private fun pickWallpaper() {
        runCatching { pickImage.launch("image/*") }
            .onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.toast_no_picker),
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
                Toast.makeText(this@MainActivity, getString(R.string.toast_bad_image), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
