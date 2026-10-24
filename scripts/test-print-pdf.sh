#!/usr/bin/env bash
# Test the PDF writer behind Android printing (android/app/.../PrintPdfWriter.java) on a desktop
# JVM: the PDF's structure and pixels, where plain and turned sheets land (rendered with pdftoppm,
# from poppler-utils) and a 64-megapixel sheet written through a 32 MB heap.
#
# Needs a JDK 17+ and pdftoppm. Writes the test PDFs to build/test-print/ (or the folder given).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/test-print}"

command -v pdftoppm >/dev/null || { echo "error: needs pdftoppm (poppler-utils)" >&2; exit 1; }

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac --release 17 -Xlint:all -Werror -d "$classes" \
  "$ROOT/android/app/src/main/java/com/appm/pdfcraft/PrintPdfWriter.java" \
  "$ROOT/scripts/PrintPdfWriterTest.java"
java -Xmx32m -cp "$classes" com.appm.pdfcraft.PrintPdfWriterTest "$OUT"
