#!/usr/bin/env bash
#
# BillShield — one command to build, install and launch on an emulator or device.
#
#   ./dev_start.sh              build debug, install, launch
#   ./dev_start.sh test         unit + instrumented tests
#   ./dev_start.sh release      signed release APK
#   ./dev_start.sh logs         tail this app's logcat
#   ./dev_start.sh clean        clean build, then everything above's prerequisites
#
# Two things this script exists to get right, because both have cost time before:
#
#   * JAVA. Robolectric needs JDK 21 (Android SDK 36 requires it) while the machine
#     default is 17, so a plain `./gradlew test` reports two failures that are nothing to
#     do with the code. This pins JDK 21 for every Gradle invocation.
#   * THE EMULATOR. It drops out regularly. This starts it if nothing is attached and
#     waits for a real boot rather than racing it.

set -euo pipefail

# ---------------------------------------------------------------- configuration ----
readonly AVD="Pixel_7"
readonly PKG="com.aistudio.mobileaccounting.vxkzp"
readonly ACTIVITY="com.example.MainActivity"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
readonly ADB="$ANDROID_HOME/platform-tools/adb"
readonly EMULATOR="$ANDROID_HOME/emulator/emulator"

# JDK 21, wherever it lives on this machine.
find_jdk21() {
  local candidates=(
    "/opt/homebrew/opt/openjdk@21"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
  )
  for c in "${candidates[@]}"; do
    if [[ -x "$c/bin/java" ]] && "$c/bin/java" -version 2>&1 | grep -q '"21'; then
      echo "$c"; return 0
    fi
  done
  return 1
}

cd "$(dirname "$0")"

JAVA_21="$(find_jdk21 || true)"
if [[ -z "$JAVA_21" ]]; then
  echo "!! No JDK 21 found. Gradle will use the default JDK."
  echo "   Robolectric tests will fail with 'Android SDK 36 requires Java 21'."
  echo "   Install one with:  brew install openjdk@21"
  GRADLE=(./gradlew)
else
  GRADLE=(./gradlew "-Dorg.gradle.java.home=$JAVA_21")
fi

say() { printf '\n\033[1;35m▸ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------------- emulator ----
device_online() {
  "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'
}

ensure_device() {
  if device_online; then
    echo "   device already attached: $("$ADB" devices | awk 'NR==2{print $1}')"
    return 0
  fi

  "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD" \
    || die "AVD '$AVD' not found. Create one in Android Studio, or edit AVD= in this script.
   Available: $("$EMULATOR" -list-avds 2>/dev/null | tr '\n' ' ')"

  say "Starting emulator '$AVD'"
  nohup "$EMULATOR" -avd "$AVD" -no-snapshot-load -no-boot-anim >/dev/null 2>&1 &

  "$ADB" wait-for-device
  printf '   waiting for boot'
  for _ in $(seq 1 60); do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      printf ' booted\n'; return 0
    fi
    printf '.'; sleep 5
  done
  die "emulator did not finish booting"
}

# ------------------------------------------------------------------- commands ----
cmd_run() {
  ensure_device
  say "Building debug APK"
  "${GRADLE[@]}" :app:assembleDebug

  say "Installing"
  "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null \
    || die "install failed"

  say "Launching"
  "$ADB" shell am force-stop "$PKG" || true
  "$ADB" logcat -c || true
  "$ADB" shell am start -n "$PKG/$ACTIVITY" >/dev/null

  sleep 4
  if "$ADB" shell pidof "$PKG" >/dev/null 2>&1; then
    printf '\n\033[1;32m✓ BillShield is running.\033[0m\n'
    echo "  logs:  ./dev_start.sh logs"
  else
    echo
    echo "!! It launched but is not running. Recent errors:"
    "$ADB" logcat -d 2>/dev/null \
      | grep -iE "FATAL EXCEPTION|AndroidRuntime|Room cannot verify|SQLiteException" \
      | head -20
    exit 1
  fi
}

cmd_test() {
  say "Unit tests"
  "${GRADLE[@]}" :app:testDebugUnitTest

  ensure_device
  say "Instrumented tests (real SQLite + Compose UI)"
  "${GRADLE[@]}" :app:connectedDebugAndroidTest

  printf '\n\033[1;32m✓ All tests passed.\033[0m\n'
}

cmd_release() {
  [[ -f keystore.properties ]] \
    || die "keystore.properties is missing — the release build has no signing password.
   It is gitignored by design; recreate it with storePassword/keyPassword/keyAlias."

  say "Building signed release APK"
  "${GRADLE[@]}" :app:assembleRelease

  local apk="app/build/outputs/apk/release/app-release.apk"
  [[ -f "$apk" ]] || die "release APK was not produced"

  local signer
  signer="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
  if [[ -n "$signer" ]]; then
    say "Signature"
    "$signer" verify --print-certs "$apk" 2>&1 | head -2
  fi

  printf '\n\033[1;32m✓ %s (%s)\033[0m\n' "$apk" "$(du -h "$apk" | cut -f1)"
  echo "  Remember: my-upload-key.jks is gitignored. Keep a backup off this machine."
}

cmd_logs() {
  device_online || die "no device attached — run ./dev_start.sh first"
  say "Tailing logs for $PKG (Ctrl-C to stop)"
  local pid
  pid="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$pid" ]]; then
    "$ADB" logcat --pid="$pid"
  else
    echo "   (app not running — showing unfiltered errors)"
    "$ADB" logcat "*:E"
  fi
}

case "${1:-run}" in
  run|"")   cmd_run ;;
  test)     cmd_test ;;
  release)  cmd_release ;;
  logs)     cmd_logs ;;
  clean)    say "Clean"; "${GRADLE[@]}" clean; cmd_run ;;
  *)        die "unknown command '$1'. Use: run | test | release | logs | clean" ;;
esac
