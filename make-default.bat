@echo off
REM Makes the new launcher the default Home. The stock launcher stays installed (not disabled).
adb shell cmd package set-home-activity com.tvhome.glass/.MainActivity
adb shell input keyevent KEYCODE_HOME
pause
