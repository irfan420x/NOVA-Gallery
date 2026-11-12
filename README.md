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

- **v1.8** — Nebula Dark Premium style pass (style only, no behavior
  changes): dark-first space theme — deep navy-black background
  (#0A0C1C), #14172B surfaces with 1dp outline borders, violet #8B5CF6
  primary with a #7C3AED → #3B82F6 gradient on primary buttons and the
  empty-state icon; album cards are 20dp with outline borders; the
  selected tab gets a violet pill; the search field is a 20dp surface
  card with a violet focus ring; favorites now show a pink star; the
  light theme is kept, restyled with soft #F4F5FB surfaces and white
  cards. Photos stay the hero — the theme frames them, it never tints
  them.
- **v1.7** — Final audit: real pagination — the Photos tab and album view
  load in pages of 500 with exact totals ("Showing X of Y"), so no photo
  is hidden behind the old 10,000 cap; album counts are exact
  (lightweight scan, no cap); Favorites are looked up per URI so old
  favorites survive; Memories scan the whole library; the "Unknown" album
  opens (it queried `= 'Unknown'` for a null bucket); an in-app "Allow
  access" button (goes to Settings when denied permanently); the viewer
  survives rotation and no longer reopens after being closed; viewer
  restore waits for the system confirmation; permanent delete reports
  honestly and keeps failed items selected; deleting an already-deleted
  file counts as deleted; foreign (VIEW-intent) photos can't be starred
  or deleted; viewer status messages are visible in the viewer; Details
  shows "—" instead of fake "0 × 0" / "0:00" / today's date, and "Folder"
  instead of "Location"; share uses ClipData so the file actually
  attaches; status timer restarts on repeat messages; error-log badge
  updates on Clear; Compose keys on all lists; dead code removed.
- **v1.6** — Second audit: "Continue without it" no longer leaves an
  infinite spinner; a failed Trash request never falls back to a permanent
  delete; Android 14 partial access ("Select photos and videos") is
  recognized; Trash loads per collection; search results refresh after a
  delete and can be shared; the open album refreshes on resume; video
  audio stops on page change; zoom resets on page change with double-tap
  zoom and clamped pan.
- **v1.5** — First audit: Trash lists only trashed items (MATCH_ONLY —
  the old MATCH_INCLUDE listed the whole library); Photos cap raised;
  "Open with Gallery" (VIEW intent) works; refresh on resume; star reads
  from FavoriteStore; mixed image+video share is `*/*`; search jobs cancel.
- **v1.4** — Advanced: Trash (30-day recycle bin, Android 11+) — deleting
  a photo moves it to the system Trash instead of destroying it, with
  Restore and permanent delete from the new Trash tab; plus Memories
  ("On this day" — photos from this day in past years) on the Albums tab.
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
