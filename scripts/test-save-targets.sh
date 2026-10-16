#!/usr/bin/env bash
# Test where a Save goes (android/app/.../SaveTargets.java, the rules behind FileStore) on a
# desktop JVM: picked files, files from "Open with", PDFs PdfCraft makes of images, closed tabs.
#
# Needs a JDK 17+.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac --release 17 -Xlint:all -Werror -d "$classes" \
  "$ROOT/android/app/src/main/java/com/appm/pdfcraft/SaveTargets.java" \
  "$ROOT/scripts/SaveTargetsTest.java"
java -cp "$classes" com.appm.pdfcraft.SaveTargetsTest
