#!/usr/bin/env bash
# Runs inside an Android emulator (API 34). Collects screenshots + logs into ./smoke_out
mkdir -p smoke_out
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK" | tee smoke_out/install.txt
adb shell pm grant com.livedub android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -W -n com.livedub/.MainActivity | tee smoke_out/launch.txt
sleep 5
adb exec-out screencap -p > smoke_out/1_home.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml smoke_out/ui.xml >/dev/null 2>&1
# enable the notification listener and start the service on a real video
adb shell cmd notification allow_listener com.livedub/com.livedub.MediaWatcher | tee smoke_out/listener.txt
adb shell am start-foreground-service -n com.livedub/.DubService --es video UF8uR6Z6KLc --es lang ar --ei duck 20 | tee smoke_out/service.txt
sleep 60
adb exec-out screencap -p > smoke_out/2_running.png
adb logcat -d -s LiveDub:I AndroidRuntime:E ActivityManager:E > smoke_out/log.txt
adb shell dumpsys activity services com.livedub > smoke_out/services.txt 2>&1
adb shell dumpsys package com.livedub | grep -i "versionName\|targetSdk" > smoke_out/package.txt
echo done
