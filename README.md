# appletv-launcher

An Apple TV-style home screen (launcher) for generic Android TV boxes.

- **Liquid-glass UI**: frosted, translucent tiles, clock and settings panel with a light-catching rim,
  specular highlights and a soft focus glow
- **Wallpaper backdrop**: 6 built-in wallpapers (default: Mask), or pick your own photo
- **Status bar**: weather (temperature, conditions, high/low), the Wi-Fi network you're connected to with signal bars and IP address, clock
- **16:9 tiles** using each TV app's own banner art (phone apps get their icon on glass)
- Focused tile zooms with a spring animation, and the backdrop picks up the app's colour
- **Launcher settings** (gear button on the home screen, or the remote's Menu key)
- D-pad friendly: arrows to move, OK to open, **hold OK** for app info / uninstall
- **Live TV** and **Movies & TV** mini apps, built in as the first two tiles (see below)

## Live TV and Movies & TV

Two tiles at the start of the home row open built-in apps (hide or show them under Settings > Hidden apps).

**Live TV** reads the [iptv-org](https://iptv-org.github.io/iptv/index.m3u) playlist (cached for 12 hours).
Filter by category chip or the search box, **OK** plays a channel full screen, **Up / Down** (or Channel +/-) zap
to the next / previous channel in the current list, **OK** shows the channel banner, **Back** returns to the list.
Streams are played with Media3 / ExoPlayer (HLS and plain files). Many free streams are offline, that's normal.

**Movies & TV** browses [TMDB](https://www.themoviedb.org) (trending, popular, top rated, search). Open a title for
its details, pick a season / episode for shows, then **Play** opens the player in a WebView:

- `https://vidly.pro/movie/{tmdb_id}`
- `https://vidly.pro/tv/{tmdb_id}/{season}/{episode}`

While a show plays, **Media Next / Previous** (or Channel +/-) jump to the next / previous episode, **Back** leaves.
The endpoints and TMDB key live in `MiniConfig` (`MiniApps.kt`).

## Settings

| Setting | Options |
| --- | --- |
| Language | System default / English / Français / Español (applies instantly, independent of the box's own language) |
| Glass style | Clear / Frosted / Dark |
| Wallpaper | 6 presets, or "Choose photo…" from storage |
| Wallpaper dimming | 0 to 70% |
| Tile size | Small / Medium / Large |
| App names | Focused only / Always |
| Backdrop glow from focused app | On / Off |
| Focus colour from app | Tints the focus ring and glow with the focused app's colour |
| Wallpaper slideshow | Off / every 1, 5, 15 or 60 minutes; cycles the built-in, custom and daily wallpapers |
| Navigation sounds | Plays the system's navigation / click sounds (follows Android's sound-effects setting) |
| Screensaver | Opens Android's screensaver settings; pick "Home screensaver" there |
| App order | Custom (A-Z until you move something) or recently used first; pinned apps always lead |
| Show HDMI / TV inputs | HDMI / composite inputs as tiles at the end of the row |
| Performance mode | Auto / Lighter / Full glass (Auto switches older Mali GPUs to the lighter look) |
| Show watched / recent titles | On / Off: a row above the tiles with the focused app's "Continue watching" / recommended titles |
| Allow reading app titles | grants `READ_TV_LISTINGS`, which the row above needs |
| Show non-TV apps | On / Off |
| Clock format | System / 12-hour / 24-hour |
| Hidden apps | choose which apps to hide |
| Show weather / location / °C-°F | auto-detect from IP, or pick a city |
| Show network | Wi-Fi name, signal, IP, or Ethernet |
| Wi-Fi name access | grants the location permission Android requires to reveal the network name |
| System shortcuts | Wi-Fi, Android settings, default home app |

On the settings screen: **Left / Right** change a value, **OK** activates, **Back** closes.

## Getting the APK (no Android Studio needed)

Every push to `main` builds an APK on GitHub:

1. Open the repo's **Actions** tab, then the latest **Build APK** run.
2. Download **appletv-launcher-debug-apk** from *Artifacts* (a zip containing `app-debug.apk`).
3. Put `app-debug.apk` on the box (USB stick or the *Downloader* app) and install it.
4. Press Home on the remote and choose **Home** with *Always*.

If the box keeps its old launcher, set it under Settings > Apps > Default apps > Home app, or run
`adb shell cmd package set-home-activity com.alejandro.tvlauncher/.MainActivity`.

## Code map

| File | What it does |
| --- | --- |
| `Glass.kt` | the liquid-glass modifier (`glass`, `glassRim`) and text helper |
| `Wallpapers.kt` | wallpaper decoding and the pre-blurred copy used behind glass |
| `LauncherSettings.kt` | all options, saved automatically |
| `Launcher.kt` | root: wallpaper layers, then home or settings |
| `Home.kt` | tiles, hero title, clock, settings button |
| `SettingsScreen.kt` | the settings pages |
| `AppRepo.kt` | loads installed apps, icons and banners |
| `ContentRepo.kt` | reads the titles an app publishes to Android TV's provider, plus the poster loader |
| `Overlays.kt` | long-press menu (pin / move / hide / info), search screen, quick-settings panel |
| `InputRepo.kt` | HDMI / composite inputs from `TvInputManager`, as tiles |
| `AmbientDream.kt` | the screensaver (`DreamService`) |
| `GpuInfo.kt` | reads the GPU name so weak GPUs can get the lighter look |
| `MiniApps.kt` | the Live TV / Movies & TV tiles, plus `MiniConfig` (TMDB key, playlist and player URLs) |
| `MiniUi.kt` | shared pieces for the mini apps: buttons, search field, artwork card |
| `LiveTv.kt` | playlist download + parser, channel browser, ExoPlayer screen |
| `Movies.kt` | TMDB client, browse / details screens, WebView player |

## Using the new features

- **Long-press OK on a tile**: pin to the front, move left / right, hide, or open the app's info.
- **Search** (magnifier, top right): app names plus every title the installed apps publish to Android TV. With nothing typed it lists what you can continue watching across apps.
- **Quick settings** (four-squares button): Wi-Fi, network, Bluetooth, display, sound, date & time, accessibility.
- **Live TV favorites**: hold OK on a channel (in the list, or while watching; the Menu key also works) to add or remove it. The "★ Favorites" chip next to "All" shows them, and zapping with Up / Down stays inside your favorites.
- **Wallpaper > Daily photo** downloads a new picture each day from picsum.photos (change `DAILY_SOURCE` in `Wallpapers.kt` to use another source).

## Status bar notes

- **Weather** comes from [Open-Meteo](https://open-meteo.com) (free, no account). With *Auto* the box's
  approximate location is guessed from its public IP address (via ipwho.is); pick a city in settings to override.
- **Wi-Fi name**: Android hides the network name from apps unless they hold the location permission **and**
  Location is switched on. The launcher asks once on first start; you can redo it under
  Settings > Status bar. It never reads or stores your actual location.

## Troubleshooting: the Home button opens the old launcher

Open **Settings > System > Default launcher (Home button)**. It shows which app Android sends the Home key to:

- **"This launcher"**: you're set. If Home still goes elsewhere, the box's firmware is overriding it; use the adb command below.
- **"Not set (system will ask)"**: press OK on the row (Android 10+ shows a "set as home app" dialog), or press Home and
  pick this launcher with **Always**.
- **Another app, e.g. `Old Launcher (com.example.launcher)`**: press OK on the row, or clear that app's defaults
  (Settings > Apps > the old launcher > *Clear defaults*) and press Home again. The package name shown there is what
  you need for the adb commands.

From a computer on the same network (box in developer mode, `adb connect <box-ip>` first):

```
adb shell cmd package set-home-activity com.alejandro.tvlauncher/.MainActivity
```

Each GitHub build is signed with a new throw-away debug key, so installing a newer APK over an older one can fail and
you have to uninstall first, which also forgets the default-launcher choice. Re-select it after reinstalling.

## Languages

English, French and Spanish are included. The launcher follows the box's language by default; **Settings > Language** (the
first row) overrides it. All text lives in `app/src/main/res/values/strings.xml` (English) and
`values-fr/strings.xml` (French). To add another language, copy `values/strings.xml` to `values-xx/strings.xml`
(e.g. `values-ar`, `values-pt`), translate the text, and add the language code to the `codes`/`names` lists in the
Language row of `SettingsScreen.kt`.

