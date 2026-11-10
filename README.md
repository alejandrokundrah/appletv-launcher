# appletv-launcher

A custom home screen (launcher) with a dark, premium look inspired by Apple TV:

- Large rounded app tiles with a glossy highlight
- Focused tile scales up with a spring animation, white rim and coloured glow
- Backdrop colour fades to match the focused app's icon
- Big app title for the focused app, clock and date top right
- D-pad friendly: arrows to move, OK to open, **hold OK** for app info / uninstall
- Press Home while on the launcher to jump back to the first tile
- Works on generic AOSP boxes (shows both TV and regular apps), Android 5.0+

## Build and install

1. Install **Android Studio** (free) and open this folder (`AppleTvLauncher`).
2. Let Gradle sync (it downloads everything automatically).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
   The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
4. Copy the APK to the box (USB stick, or `adb connect <box-ip>` then
   `adb install app-debug.apk`).
5. Press the Home button on the remote and choose **Home** with *Always*.

If the box asks every time, or keeps its old launcher, set the default under
Settings > Apps > Default apps > Home app. If your box does not offer that,
run `adb shell cmd package set-home-activity com.alejandro.tvlauncher/.MainActivity`.

## Customising

All the look lives in `HomeScreen.kt`:

| What | Where |
| --- | --- |
| Tile size / corner roundness | `TileSize`, `TileShape` at the top |
| Focus zoom and spring feel | `AppTile` > `scale` |
| Spacing between tiles | `LazyRow` > `horizontalArrangement` |
| Backdrop colours | `Brush.verticalGradient` in `HomeScreen` |
| Title size | the hero `Column` |

App loading and sorting is in `AppRepo.kt`.
