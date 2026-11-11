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

## Settings

| Setting | Options |
| --- | --- |
| Glass style | Clear / Frosted / Dark |
| Wallpaper | 6 presets, or "Choose photo…" from storage |
| Wallpaper dimming | 0 to 70% |
| Tile size | Small / Medium / Large |
| App names | Focused only / Always |
| Backdrop glow from focused app | On / Off |
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

## Status bar notes

- **Weather** comes from [Open-Meteo](https://open-meteo.com) (free, no account). With *Auto* the box's
  approximate location is guessed from its public IP address (via ipwho.is); pick a city in settings to override.
- **Wi-Fi name**: Android hides the network name from apps unless they hold the location permission **and**
  Location is switched on. The launcher asks once on first start; you can redo it under
  Settings > Status bar. It never reads or stores your actual location.
