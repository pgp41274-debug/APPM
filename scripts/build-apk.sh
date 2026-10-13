#!/usr/bin/env bash
# Build the signed PdfCraft APK into dist/PdfCraft-<version>.apk.
#
# Needs build/web-assets/app/pdfcraft/ (run scripts/build-web.sh first) and a JDK 17+.
# Uses $ANDROID_HOME / $ANDROID_SDK_ROOT when set; otherwise installs the Android command-line
# tools, platform and build-tools into ~/android-sdk.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPILE_SDK=36
BUILD_TOOLS=36.1.0
CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

[ -f "$ROOT/build/web-assets/app/pdfcraft/index.html" ] ||
  { echo "error: build/web-assets/app/pdfcraft is missing; run scripts/build-web.sh first" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

sdkmanager_bin() {
  for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  command -v sdkmanager || true
}

SDKMANAGER="$(sdkmanager_bin)"
if [ -z "$SDKMANAGER" ]; then
  log "installing Android command-line tools into $SDK"
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
  SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
fi

if [ ! -d "$SDK/platforms/android-$COMPILE_SDK" ] || [ ! -d "$SDK/build-tools/$BUILD_TOOLS" ]; then
  log "installing platform android-$COMPILE_SDK and build-tools $BUILD_TOOLS"
  yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDKMANAGER" --sdk_root="$SDK" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "platform-tools" >/dev/null
fi

log "assembling the release APK"
cd "$ROOT/android"
./gradlew --no-daemon --stacktrace assembleRelease

VERSION="$(cat "$ROOT/build/web-assets/app/pdfcraft/VERSION" 2>/dev/null || echo dev)"
mkdir -p "$ROOT/dist"
APK="$ROOT/dist/PdfCraft-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
"$SDK/build-tools/$BUILD_TOOLS/apksigner" verify "$APK"
log "wrote $APK ($(du -h "$APK" | cut -f1))"
