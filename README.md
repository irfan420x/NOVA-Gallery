# NOVA Gallery

The photo and video gallery for **NOVA OS** — a modern gallery app for Android 10+.

- **Albums** — photos and videos grouped by folder, with cover thumbnails,
  item counts and total sizes
- **Photos** — all media in one grid, newest first, with adjustable grid size
- **Viewer** — pinch-zoom for photos, video playback with controls, prev/next
  navigation, share, favorite, delete (with the system confirmation on
  Android 10+), and a details sheet (name, type, date, size, resolution,
  duration, album, location)
- **Favorites** — star any photo or video to pin it in its own tab
- **Search** — find photos by file name
- **Multi-select** — long-press to select several items, then share or delete
  them together (one system confirmation for the whole batch on Android 11+)
- **Dark / light theme**, persisted
- **In-app Error log** — every crash and failed operation is recorded with its
  time, so problems can be reported with the exact error

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

- **v1.0** — Albums, Photos grid, viewer with pinch-zoom and video playback,
  favorites, search, multi-select share/delete, themes, Error log.

## License

MIT — see [LICENSE](LICENSE).
