# Third-party material

The APK bundles PdfCraft's browser build and the material PdfCraft itself bundles. Each item keeps its own licence. The licence texts ship inside the APK under `assets/app/pdfcraft/licenses/`, copied there by `scripts/build-web.sh`.

| Material | Source | Licence |
| --- | --- | --- |
| PdfCraft v0.13.0 (compiled to WebAssembly), © 2026 ArtCraft Team and the PdfCraft contributors | https://github.com/storytold/pdfcraft | MIT OR Apache-2.0 |
| PdfCraft app icon (`assets/app-icon/pdfcraft-1024.png`), used for the launcher and loading screen | PdfCraft | MIT OR Apache-2.0 |
| Lucide icons | via PdfCraft, and 68 of them in `android/app/src/main/assets/app/icons/` for the phone controls (licence in that folder) | ISC |
| Inter, JetBrains Mono, Dancing Script typefaces | via PdfCraft | SIL Open Font License 1.1 |
| BIZ UDPGothic, BIZ UDMincho, Shippori Mincho, Noto Sans Thai, Noto Sans Arabic, Noto Sans CJK SC (the families craft-fonts provides; PdfCraft's web build embeds a subset) | https://github.com/storytold/craft-fonts at `3d9ab3a` | SIL Open Font License 1.1 |
| hayro, lopdf, egui/eframe and PdfCraft's other Rust dependencies | crates.io, via PdfCraft | See PdfCraft's `NOTICE` and `ATTRIBUTION.md` |
| AndroidX Activity, Core and WebKit | https://developer.android.com/jetpack/androidx | Apache-2.0 |

The ArtCraft name, wordmark and logos are trademarks of the ArtCraft Team and are not used here. `patches/pdfcraft/` holds this project's only changes to PdfCraft's source, offered under PdfCraft's own licence terms.
