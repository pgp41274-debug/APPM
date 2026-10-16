# PdfCraft for Android

[PdfCraft](https://github.com/storytold/pdfcraft) is the ArtCraft team's open-source PDF reader and editor: a clean-room Rust reimplementation of Acrobat (view, annotate, fill and sign, organize, combine, split, protect, redact, compress). It ships for desktop and the web but not for phones. This repository packages it as an Android app you can install from an APK.

Nothing here is reverse engineered. PdfCraft is MIT OR Apache-2.0 licensed, so the build compiles PdfCraft's own browser (WebAssembly) version from its source, pinned to release **v0.7.1**, and wraps it in a small native Android shell. The app runs entirely offline: it has no INTERNET permission, no account and no telemetry.

## Install on your phone

1. Get the APK: download `PdfCraft-0.7.1.apk` from this repository's [latest release](../../releases/latest), or from the `PdfCraft-apk` artifact of a workflow run under **Actions**.
2. Open the downloaded file. Android asks you to allow your browser (or Files app) to "install unknown apps"; allow it, go back, and tap **Install**.
3. Open **PdfCraft** from your app drawer.

You need Android 10 or newer with a current **Android System WebView** (it updates through the Play Store). Every build is signed with the same key, so a newer APK installs over the old one and keeps your settings.

## Using it

- **Open a PDF**: tap **Open** in PdfCraft, choose PdfCraft under "Open with" in your Files app, or share a PDF to PdfCraft from any app. Shared files open in new tabs, even while PdfCraft is running.
- **Save** (Ctrl+S, or the save icon) writes back to the original file when the app that opened it allowed writing. Otherwise the first Save puts a copy in `Downloads/PdfCraft/`, and later saves of that document update that copy. Every open document is tracked by its own ID, so a Save never lands on a different file that happens to share its name. A picture or text file you open becomes a new PDF, which is saved as a copy, never over the original file. A toast says where every save went.
- Saving over a file never leaves it half-written. PdfCraft copies what the file held first and puts it back if the write fails; if Android closes the app in the middle of a save, the next start finishes it. A save that can't be written anywhere opens again in a new tab, so the work isn't lost.
- **Save as** and **exports** (pages as images, CSV, extracted files and so on) open Android's "save to" picker. If you cancel a Save As, a copy is still kept in `Downloads/PdfCraft/`, so no work is lost.
- **Print** opens Android's print dialog: printers on your network, or **Save as PDF**.
- **Links** in PdfCraft (Help, release notes, credits) open in your browser.
- **Back** sends PdfCraft to the background with its documents still open, like Home does. Android may close a background app at any time, though, so save first.

### Tips for a phone or tablet screen

The app draws its own controls around PdfCraft's page, laid out as phone and tablet PDF apps lay them out (below). PdfCraft's own desktop interface is not used on a touch screen.

- Dialogs larger than the screen (Print, Properties) and the command palette scroll. Drag inside them to reach their buttons.
- **Settings (the gear on the home screen) ▸ Interface size** makes everything smaller (down to 75%) or larger.

## Limitations

- On a phone or a tablet, the controls are laid out as PDF apps for phones and tablets lay them out (`shell/phone.js`; PdfCraft runs with `&chrome=host`, patches 0007 to 0009, and draws only the page, its dialogs and the open panel):
  - **Home:** open a file, make a PDF from images, a blank PDF, combine files; the open files.
  - **Top bar:** ← home, Undo (and Redo when there is one), Search, Share (Android's share sheet), and ⋮ for the file: View settings (Continuous, Single page, Two pages, Night mode, Fit width, Fit page, Full screen), Pages, Bookmarks, Comment list, Attachments, Go to page, Add bookmark, Save, Save a copy, Share, Print, Document properties, Close file.
  - **Bottom bar:** Edit PDF, Comment, Highlight, Draw, Fill & Sign and More tools (a grid: Organize pages, Export PDF, Combine files, Compress PDF, Set password, and more; All commands searches every PdfCraft command).
  - **A mode** (Edit PDF, Comment, Highlight, Draw, Fill & Sign): ✓ at the top left ends it; its tools run along the bottom with the colour (and, for the pen and shapes, the thickness) after a divider; a hint says what to do. Edit PDF outlines the text you can edit and offers Organize pages, Add text and Add image. A sticky note is written in a box along the bottom.
  - **Selected text:** hold a finger on a word to select it, and only that: two, three or four quick taps select nothing (PdfCraft's mouse double, triple and quadruple click select a word, a line and the page, which a finger reaches by accident). Drag the handles to select more. The card above it comments, highlights, underlines, strikes out, edits or copies the text.
  - **Search:** the top bar turns into the search box, with the number of matches and arrows between them.
  - **Page:** a tap hides the bars and another brings them back; the page number shows a while after the page changes, and a tap on it goes to a page. Back closes, in turn, a sheet, the comment box, a selection, the search, full screen, a panel and a mode, then goes home.
  - **Tablets** (600 dp and wider) get the same controls with the room used: the file's name on the top bar, the buttons of the bottom bar and of a mode together in the middle rather than spread across the width, sheets as cards in the middle (More tools as a six-column grid), and a home screen in a column. A panel (Pages, Bookmarks, Comments...) docks beside the page, about a third of the width, with its own close button, and the bars stay. A mouse or trackpad on a tablet keeps PdfCraft's double and triple click; only a finger is limited to a long press.
- One finger scrolls the page (with the Select tool), the Pages panel and the page grid; two fingers scroll and pinch-zoom with any tool, so the pen and the highlighter keep one finger for themselves. To move pages in the Pages panel, long-press one first; in the page grid, tap to select first.
- Edit PDF edits text PdfCraft can take apart: text drawn on the page in its own fonts. It finds none in many PDFs printed from a browser and in some forms; it then says so, and Add text and Add image still work.
- View ▸ Full screen mode hides Android's status and navigation bars too. Swipe from an edge to see them for a moment; Back leaves full screen.
- Anything PdfCraft leaves out of its own browser build is missing here too. The main gaps are OCR (its models only ship with the desktop app) and batch actions on folders.
- PdfCraft runs as WebAssembly on one thread, so very large or complex PDFs render slower than in the desktop app. The APK is about 23 MB, and each start compiles a 44 MB WebAssembly module, which can take a few seconds on an older phone.
- Signing uses `android/signing/sideload.jks`, a key committed on purpose (passwords in `android/app/build.gradle.kts`) so every build, local or CI, installs as an update. Anyone with access to this repository could sign an "update" with it. That is fine for a personal sideloaded app. To use a private key, add the repository secrets `PDFCRAFT_KEYSTORE_B64` (the keystore, base64), `PDFCRAFT_KEYSTORE_PASSWORD`, `PDFCRAFT_KEY_ALIAS` and `PDFCRAFT_KEY_PASSWORD`. Android refuses to update across a key change, so uninstall the old app once when you switch.

## How it works

```
Android app (android/)                         assets/app/
┌──────────────────────────────┐              ┌──────────────────────────────────────┐
│ MainActivity                 │  serves      │ shell/index.html + shell.js          │
│  WebView ◀──── WebViewAsset- │─────────────▶│   └─ <iframe> pdfcraft/index.html    │
│                Loader        │  https://    │         ?host=parent                 │
│  ShellBridge (AndroidShell) ◀┼──────────────┤      PdfCraft's wasm build, unmodified│
│  FileStore  (open / save)    │  JS bridge   │      except patches/pdfcraft/        │
│  Printer    (PrintManager)   │              └──────────────────────────────────────┘
└──────────────────────────────┘
```

- `MainActivity` serves the APK's assets at `https://appassets.androidplatform.net/app/` (with `application/wasm` for the module) and serves files handed in by other apps at `/incoming/<id>`. It also connects PdfCraft's Open to Android's document picker and handles "Open with" and share intents (`content://` only: a `file://` path would be read with the app's own rights). It handles every configuration change in place (rotation, Dark theme, wallpaper colours), because recreating it would close every open document. The launcher icon starts it through `LauncherActivity`, so that tapping the icon while a picker is open brings the picker back rather than closing it.
- PdfCraft's browser build already has a **host bridge** for embedding (`apps/pdfcraft-web/src/main.rs`, `mod host`). Started with `?host=parent` in a same-origin frame, it posts `pdfcraft:ready`, `pdfcraft:save {name, saveAs, bytes, id, doc}`, `pdfcraft:closed {id, doc}` and `pdfcraft:dirty`, and accepts `pdfcraft:open {name, bytes, id}` (`id`, `doc` and `pdfcraft:closed` come from patch 0003). `shell.js` relays these to Java (`ShellBridge`) in the order PdfCraft sent them, with file bytes in base64 slices. `FileStore` picks where a save goes by the document's `id` (files Android handed in) or `doc` number (everything else), never by name alone. When a tab closes, or a file Android sent never becomes a document of its own, `pdfcraft:closed` lets go of the file its saves went to (once those saves are written), so that file opened again saves back to it.
- `shell.js` also covers what a browser does but a WebView doesn't. Downloads of `blob:` URLs become exports. `window.open` links go to the browser. PdfCraft's print sheets (PNG pages in a hidden frame) go to `Printer`, which streams them into a PDF one strip at a time (`PrintPdfWriter`, about 16 MB of memory however long the document) for Android's `PrintManager`.
- `patches/pdfcraft/` holds the only changes to PdfCraft. Below 720 points, dialogs (0001) and the command palette (0002) fit inside the screen and scroll instead of running off it; desktop sizes are unaffected. 0003 gives documents from the host an ID that comes back with every save, and tells the host when a document closes. 0004 brings a file opened with PdfCraft to the front (upstream opens a late file from the host in the background). 0005 fits the right-hand rail to a short screen. 0006 makes a finger drag scroll the page and the page lists (upstream gives every drag to text selection or page moving, so touch could not scroll), and reports Full screen mode to the host, which hides the system bars (the browser build can't take the screen itself). 0007 adds `&chrome=host`: PdfCraft draws only the page, its dialogs and the open panel (as a sheet from the bottom), reports what a host's controls need (`pdfcraft:state`) and takes commands, tools, panels, tabs and pages from it. 0008 adds what the phone's modes need: two fingers scroll with any tool (a pen stroke the first finger began is dropped), a finger held on a word is the only way to select text on a phone (no word, line or page by two to four quick taps), and the host can act on a selection and move its ends, set a comment tool's colour, width and opacity, write the comment being added, search, turn Night mode (white on black) on and off, and share the document. It also tells the host how many paragraphs Edit text can edit, draws Edit text's boxes dashed (a finger can't hover them), opens no desktop menu on a long press, and counts PdfCraft's frame as focused while the host draws the controls: a tap on them took the focus, which had left Edit text and pinch-zoom inert until the page was tapped. 0009 docks the open panel beside the page when the host asks (`&panel=side`, tablets) and reports where it is (`panelRect`), and limits the no-double-click rule to fingers, which it knows from the browser's own `pointerType` (a frame can run between a tap's `pointerdown` and its `touchstart`, so egui's touch events miss some taps).
- On first launch on a phone, the shell seeds PdfCraft's saved settings with the Tools panel closed. PdfCraft's own settings take over from then on.

## Build it yourself

Needs Linux or macOS, Rust 1.95+ (rustup), JDK 17+, and about 6 GB of disk.

```sh
scripts/build-web.sh     # PdfCraft v0.7.1 -> wasm, into build/web-assets (about 10 min the first time)
scripts/build-apk.sh     # installs the Android SDK into ~/android-sdk if needed -> dist/PdfCraft-0.7.1.apk
```

`build-web.sh` clones PdfCraft and [craft-fonts](https://github.com/storytold/craft-fonts) at pinned revisions, applies `patches/pdfcraft/`, and builds with [trunk](https://trunkrs.dev). If the source build fails, it falls back to unpacking PdfCraft's official `pdfcraft-web-0.7.1.zip` release, which is the same code without these patches (CI sets `PDFCRAFT_NO_FALLBACK=1` so a release never ships without them). `scripts/build-web.sh --prebuilt` uses that zip directly. To follow a newer PdfCraft release, set `PDFCRAFT_VERSION` (and refresh the patches and the docking seed in `shell.js` if they no longer apply).

`scripts/test-android.mjs` runs the APK itself on an Android emulator (Android 16, with its own System WebView) and drives it over adb: start from the launcher, "Open with" a writable and a read-only PDF, edit and save (in place, and to `Downloads/PdfCraft`), Save As through Android's picker (and the launcher icon over it), the print dialog, rotation, Dark theme, a theme colour change, Android ending the app in the background, and a crashed page. It saves device screenshots and the logcat. `scripts/test-shell.mjs` runs PdfCraft inside the shell in Chromium (the engine of Android System WebView) at a Pixel 7's screen size and scale. It drives open, edit, save, save as, closing tabs, export, links and PdfCraft's own Print button against a stand-in for the Java bridge, and writes screenshots to `build/test-shots/`. `scripts/test-print-pdf.sh` checks the print PDF writer on a desktop JVM: structure, every pixel, where plain and turned sheets land (rendered with `pdftoppm`) and a 64-megapixel sheet in a 32 MB heap. `scripts/test-save-targets.sh` checks, also on a desktop JVM, which file each Save goes to (`SaveTargets`): picked files, "Open with" files, PDFs made from pictures, closed tabs.

```sh
npm install --no-save playwright@1.56.1 && npx playwright install chromium
node scripts/test-shell.mjs
```

`.github/workflows/android.yml` runs the builds and all four test suites (the emulator one on a KVM-accelerated `ubuntu-latest` runner) on every pull request and push to `main`. A push to `main` publishes the APK as a release once every test has passed.

## Licences

PdfCraft is © the ArtCraft Team and the PdfCraft contributors, MIT OR Apache-2.0. The APK carries its licences, `NOTICE`, `ATTRIBUTION.md` and the font licences under `assets/app/pdfcraft/licenses/`. See [THIRD_PARTY.md](THIRD_PARTY.md). This project is not affiliated with or endorsed by the ArtCraft Team, and it uses none of the ArtCraft trademarks (the ArtCraft name and the logos in PdfCraft's `docs/brand/`).
