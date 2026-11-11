# NOVA Gallery

The photo and video gallery for **NOVA OS** — fast, clean, Android 10+.

- **Albums** — MediaStore buckets with covers, counts and total sizes
- **Photos grid** — newest first, adjustable columns (2/3/4, persisted)
- **Viewer** — swipe left/right to change photos, pinch-zoom (swiping locks
  while zoomed in), video playback with controls, prev/next buttons,
  share, favorite, delete (with system confirmation), details sheet
- **Favorites tab** — starred photos and videos, persisted
- **Search** by file name
- **Multi-select** — long-press to select, then share or delete in a batch
  (system confirmation on Android 10+)
- **Dark / light theme**, persisted
- **In-app Error log** — every crash and failed operation is recorded with
  its time, so problems can be reported with the exact error

## Build

This project does **not** use Gradle. It builds with a manual pipeline
(`aapt2` + `kotlinc` + `d8` + `apksigner`), which is what `tools/build-apk.sh` runs.

### Prerequisites

- Android SDK with `platforms/android-35` and `build-tools/35.0.0`
  (set `ANDROID_HOME`, e.g. `~/android-sdk`)
- JDK 17 (set `JAVA_HOME`)
- The NOVA Files toolchain — this build reuses the JDK, kotlinc compiler and
  dependency jars from a NOVA-Files checkout. Check it out next to this repo
  (or set `NOVA_FILES_ROOT` to its path). The Kotlin compiler 2.0.21 jars
  (`kotlin-compiler-embeddable-2.0.21.jar`, the Compose compiler plugin)
  and `trove4j` come from that checkout (see `tools/build-apk.sh`).

### Steps

```bash
# 1. Fetch the dependencies into deps/ (not committed — AARs/JARs)
python3 tools/resolve_deps.py deps/ $(cat tools/deps.txt)

# 2. Extract them into the build tree
python3 tools/prep_deps.py

# 3. Build the APK
bash tools/build-apk.sh
# -> build/apk/nova-gallery-debug.apk
```

The APK is signed with a debug keystore generated on first build.
Minimum SDK 29 (Android 10), target SDK 35.

## Version history

- **v1.3** — The swipe bug, found by a full audit: the zoomable image's
  `transformable()` modifier consumed every single-finger horizontal drag
  as a "pan", so the HorizontalPager never saw the swipe and photos never
  changed. Panning is now gated to the zoomed state (`canPan`), so at 1x
  the pager gets the swipe and zoomed in the image pans. Also from the
  audit: multi-select is now keyed by content URI (an image and a video
  can share the same MediaStore row id, so the old id-based selection
  treated them as one item); the album grid refreshes after closing the
  viewer; search escapes `%`/`_` so they are searched literally.
- **v1.2** — Swipe to change photos in the viewer (HorizontalPager; zoom
  locks paging while zoomed in) and the system back button now steps back
  through the layers — viewer → grid, album → albums, search → clear,
  selection → clear, error log → close — instead of exiting the app.
- **v1.1** — Two fixes: the MediaStore query rejected `LIMIT` in the sort
  order, so the gallery opened empty; and tapping "Allow access" crashed —
  the Activity Result API always generates a permission request code of
  65536 or higher, and `FragmentActivity` rejects any code above the lower
  16 bits. `MainActivity` now extends `ComponentActivity`.
- **v1.0** — Albums, Photos grid, viewer with pinch-zoom and video
  playback, Favorites, search, multi-select share/delete, themes, Error log.

## License

MIT — see [LICENSE](LICENSE).
