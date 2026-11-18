#!/usr/bin/env bash
# Run as one script so loops and conditionals are parsed by Bash as a complete program.
set +e

mkdir -p app/build/reports/android-emulator
ready=0

for attempt in $(seq 1 90); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$boot_completed" = "1" ] && adb shell cmd package list packages >/dev/null 2>&1; then
    ready=1
    echo "Android package manager is ready (attempt $attempt)."
    break
  fi
  echo "Waiting for Android package manager ($attempt/90)..."
  sleep 5
done

if [ "$ready" -ne 1 ]; then
  echo "::error::Android package manager did not become ready within 7.5 minutes."
  adb shell getprop > app/build/reports/android-emulator/getprop.txt 2>&1 || true
  adb shell service list > app/build/reports/android-emulator/services.txt 2>&1 || true
  adb logcat -d -v threadtime > app/build/reports/android-emulator/logcat.txt 2>&1 || true
  exit 1
fi

gradle connectedDebugAndroidTest --stacktrace --info
test_exit=$?
adb logcat -d -v threadtime > app/build/reports/android-emulator/logcat.txt 2>&1 || true
adb shell dumpsys activity activities > app/build/reports/android-emulator/activities.txt 2>&1 || true
adb shell dumpsys package com.app.musicplayer > app/build/reports/android-emulator/package.txt 2>&1 || true
exit "$test_exit"
