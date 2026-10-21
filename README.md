# Glass TV launcher (Apple TV style)

Plain-Java Android TV launcher: black background, dock (4 apps), 4-column grid,
one glass highlight, Inputs / Wi-Fi / Settings pills. No blur, no video, no network polling.

## Build
Upload this folder to a GitHub repo. Actions > "Build APK" > download artifact `tv-launcher-apk` (contains `app-debug.apk`).

## Install
Copy `app-debug.apk`, `install.bat`, `make-default.bat` into platform-tools. Run `install.bat` (preview), then `make-default.bat`.

## Change the dock order / apps
Edit `DOCK_PACKAGES` at the top of `app/src/main/java/com/tvhome/glass/MainActivity.java`
(package names, left to right, 4 slots). Hide apps from the grid with `HIDDEN` in the same place.
Commit the change on GitHub, download the new APK, run `install.bat` again.

## Wallpaper
The background is pure black by design. To use a colour, change `root.setBackgroundColor(Color.BLACK)` in `buildUi()`.

## Undo
- Back to the stock home: `adb shell cmd package set-home-activity com.google.android.tvlauncher/<its home activity>`
  or run `restore.bat` if you disabled packages, then Settings > Apps > Default apps > Home.
- Uninstall: `adb shell pm uninstall com.tvhome.glass`

## Font
Outfit (SIL Open Font License), downloaded at build time. If the download fails the launcher uses the system light font.
