#!/usr/bin/env bash
# Build PdfCraft's browser (wasm32) version from source into build/web-assets/app/pdfcraft/,
# where the Android project picks it up as APK assets.
#
# Usage: scripts/build-web.sh [--prebuilt]
#
#   (default)    clone storytold/pdfcraft at $PDFCRAFT_REF and build it with trunk
#   --prebuilt   unpack the official pdfcraft-web-<version>.zip release asset instead
#
# If the source build fails, the script falls back to the prebuilt release so a build always
# ends with a working site (set PDFCRAFT_NO_FALLBACK=1 to make a source failure fatal). The
# prebuilt site lacks the phone patches in patches/pdfcraft/, so it is a fallback only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PDFCRAFT_VERSION="${PDFCRAFT_VERSION:-0.13.0}"
PDFCRAFT_REF="${PDFCRAFT_REF:-v$PDFCRAFT_VERSION}"
# The craft-fonts revision PdfCraft's own CI pins (Japanese + Thai fonts, as in official releases).
CRAFT_FONTS_REF="${CRAFT_FONTS_REF:-3d9ab3a132ed0eff457e424226a435a901da30d3}"
TRUNK_VERSION="${TRUNK_VERSION:-0.21.14}"

SRC="$ROOT/build/src"
TOOLS="$ROOT/build/tools"
OUT="$ROOT/build/web-assets/app/pdfcraft"
mkdir -p "$SRC" "$TOOLS"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

check_site() {
  [ -f "$OUT/index.html" ] || { echo "error: $OUT/index.html missing" >&2; return 1; }
  ls "$OUT"/*.wasm >/dev/null 2>&1 || { echo "error: no .wasm in $OUT" >&2; return 1; }
  # Paths must be relative: the site is served from /app/pdfcraft/ inside the APK.
  if grep -Eq '(src|href)="/[^/]' "$OUT/index.html"; then
    echo "error: $OUT/index.html has root-absolute URLs" >&2
    return 1
  fi
  echo "$PDFCRAFT_VERSION" > "$OUT/VERSION"
  log "site ready: $OUT ($(du -sh "$OUT" | cut -f1))"
}

prebuilt() {
  local name="pdfcraft-web-$PDFCRAFT_VERSION"
  local zip="$TOOLS/$name.zip"
  log "using the official prebuilt $name.zip"
  [ -f "$zip" ] || curl -fL --retry 4 -o "$zip" \
    "https://github.com/storytold/pdfcraft/releases/download/v$PDFCRAFT_VERSION/$name.zip"
  rm -rf "$TOOLS/unzip" "$OUT"
  mkdir -p "$TOOLS/unzip" "$(dirname "$OUT")"
  (cd "$TOOLS/unzip" && unzip -q "$zip")
  mv "$TOOLS/unzip/$name" "$OUT"
  check_site
}

fetch() { # fetch <url> <dir> <ref>
  local url=$1 dir=$2 ref=$3
  if [ ! -d "$dir/.git" ]; then
    git init -q "$dir"
    git -C "$dir" remote add origin "$url"
  fi
  git -C "$dir" fetch -q --depth 1 origin "$ref"
  git -C "$dir" checkout -q --force FETCH_HEAD
  # Files a patch added last time would stop it applying again (ignored build output stays).
  git -C "$dir" clean -fdq
}

from_source() {
  log "fetching pdfcraft $PDFCRAFT_REF and craft-fonts $CRAFT_FONTS_REF"
  fetch https://github.com/storytold/pdfcraft "$SRC/pdfcraft" "$PDFCRAFT_REF"
  fetch https://github.com/storytold/craft-fonts "$SRC/craft-fonts" "$CRAFT_FONTS_REF"

  # Small changes for phone screens (patches/pdfcraft/), on top of the pinned release.
  for patch in "$ROOT"/patches/pdfcraft/*.patch; do
    [ -e "$patch" ] || continue
    log "applying $(basename "$patch")"
    git -C "$SRC/pdfcraft" apply --whitespace=nowarn "$patch"
  done

  rustup target add wasm32-unknown-unknown >/dev/null

  local trunk="$TOOLS/trunk-$TRUNK_VERSION/trunk"
  if [ ! -x "$trunk" ]; then
    log "downloading trunk $TRUNK_VERSION"
    mkdir -p "$(dirname "$trunk")"
    curl -fL --retry 4 "https://github.com/trunk-rs/trunk/releases/download/v$TRUNK_VERSION/trunk-x86_64-unknown-linux-gnu.tar.gz" |
      tar -xz -C "$(dirname "$trunk")"
  fi

  log "building the wasm site (this takes a while)"
  rm -rf "$OUT"
  mkdir -p "$(dirname "$OUT")"
  (
    cd "$SRC/pdfcraft/apps/pdfcraft-web"
    # craft-libs is a git dependency; the git CLI honours proxy and credential config.
    export CARGO_NET_GIT_FETCH_WITH_CLI=true
    export CRAFT_FONTS_DIR="$SRC/craft-fonts"
    # Fail rather than quietly build a site without the Japanese, Thai and other UI fonts.
    export CRAFT_FONTS_REQUIRED=1
    export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/target}"
    "$trunk" build --release --dist "$OUT"
  )
  # The licences of everything the site embeds: PdfCraft, its icons and fonts, and craft-fonts
  # (the official release zip carries the same files).
  mkdir -p "$OUT/licenses"
  cp "$SRC/pdfcraft"/{LICENSE-MIT,LICENSE-APACHE,NOTICE,ATTRIBUTION.md} "$OUT/licenses/"
  cp "$SRC/pdfcraft"/assets/fonts/OFL-*.txt "$SRC/pdfcraft/assets/icons/LICENSE-lucide.txt" "$OUT/licenses/"
  for f in "$SRC/craft-fonts"/fonts/*/OFL.txt; do
    cp "$f" "$OUT/licenses/OFL-$(basename "$(dirname "$f")").txt"
  done
  check_site
}

if [ "${1:-}" = "--prebuilt" ]; then
  prebuilt
  exit 0
fi

# Not `if ! from_source`: bash ignores `set -e` inside a function called as a condition, so a
# failed fetch or patch would carry on and still count as a successful build. A subshell with
# its own `set -e` stops at the first failing step.
set +e
(set -e; from_source)
status=$?
set -e
if [ "$status" -ne 0 ]; then
  if [ "${PDFCRAFT_NO_FALLBACK:-0}" = "1" ]; then
    echo "error: source build failed" >&2
    exit 1
  fi
  echo "warning: the source build failed; falling back to the official prebuilt site" >&2
  prebuilt
fi
