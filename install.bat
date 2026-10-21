@echo off
REM Installs the launcher and previews it. Does NOT make it the default.
REM Put app-debug.apk next to this file (inside platform-tools). Change the IP if your TV changes.
set TV=192.168.1.9:5555
adb connect %TV%
adb push app-debug.apk /data/local/tmp/tvhome.apk
adb shell pm install -r /data/local/tmp/tvhome.apk
adb shell pm grant com.tvhome.glass android.permission.ACCESS_FINE_LOCATION
adb shell am start -n com.tvhome.glass/.MainActivity
echo.
echo Look at the TV. If it looks right, run make-default.bat
pause
