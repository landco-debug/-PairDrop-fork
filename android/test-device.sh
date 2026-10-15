#!/bin/bash
set -euo pipefail
trap 'adb logcat -d -t 1500 > android/logcat.log || true' EXIT
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant site.landco.pairdrop.fork android.permission.POST_NOTIFICATIONS
adb shell pm grant site.landco.pairdrop.fork android.permission.ACCESS_LOCAL_NETWORK
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am instrument -w site.landco.pairdrop.fork.test/site.landco.pairdrop.fork.ClipboardInstrumentation | tee android/instrumentation.log
rg 'CLIPBOARD_INTEGRATION_PASS: API=37' android/instrumentation.log
