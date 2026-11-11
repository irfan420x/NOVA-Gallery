#!/bin/bash
# Manual Android build for NOVA Gallery (no Gradle: the Gradle daemon protocol
# is broken in this VM because Java loopback TCP is intercepted).
# Reuses the NOVA Files toolchain (JDK, kotlinc, SDK) and its vendored
# dependency jars/AARs — the Dialer needs the same Compose + AndroidX set.
# Steps: aapt2 compile+link, javac R.java, kotlinc (embeddable) + Compose
# plugin, d8, zipalign, apksigner.
set -e
# Portable paths: this repo's root is the script's parent directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# The shared NOVA Files toolchain (JDK, kotlinc, dependency jars) is reused.
# Set NOVA_FILES_ROOT, or check out NOVA-Files next to this repo; the
# $HOME/workspace/nova-files location is the last fallback.
if [ -n "$NOVA_FILES_ROOT" ]; then
    FILES_ROOT="$NOVA_FILES_ROOT"
elif [ -d "$ROOT/../nova-files" ]; then
    FILES_ROOT="$ROOT/../nova-files"
elif [ -d "$ROOT/../NOVA-Files" ]; then
    FILES_ROOT="$ROOT/../NOVA-Files"
else
    FILES_ROOT="$HOME/workspace/nova-files"
fi
export JAVA_HOME="${JAVA_HOME:-$FILES_ROOT/tools/jdk-17.0.21+9}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
KEYTOOL="$JAVA_HOME/bin/keytool"
JAVAC="$JAVA_HOME/bin/javac"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
BUILD="$ROOT/build"
SRC="$ROOT/app/src/main"
TOOLS="$FILES_ROOT/tools"

rm -rf "$BUILD/apk" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/rclasses" "$BUILD/flat" "$BUILD/flat-zips"
mkdir -p "$BUILD/apk" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/rclasses" "$BUILD/flat"
# Reuse the NOVA Files dependency lists (absolute paths — valid here).
cp "$FILES_ROOT/build/jars.txt" "$BUILD/jars.txt"
cp "$FILES_ROOT/build/aar-packages.txt" "$BUILD/aar-packages.txt"

echo "== 1. aapt2 compile resources =="
mkdir -p "$BUILD/flat-zips"
"$AAPT2" compile --dir "$SRC/res" -o "$BUILD/flat-zips/app.zip" 2>&1 | grep -v "^$" || true
while read -r pkg resdir; do
    name=$(basename "$(dirname "$resdir")")
    "$AAPT2" compile --dir "$resdir" -o "$BUILD/flat-zips/$name.zip" 2>&1 | grep -v "^$" || true
done < "$BUILD/aar-packages.txt"
for z in "$BUILD"/flat-zips/*.zip; do
    name=$(basename "$z" .zip)
    mkdir -p "$BUILD/flat/$name"
    unzip -q -o "$z" -d "$BUILD/flat/$name"
done

echo "== 2. aapt2 link =="
LINK_ARGS=(-I "$ANDROID_JAR" --manifest "$SRC/AndroidManifest.xml"
    --auto-add-overlay --java "$BUILD/gen"
    --min-sdk-version 29 --target-sdk-version 35
    --version-code 5 --version-name 1.4
    -o "$BUILD/apk/app-unsigned.apk")
while read -r pkg resdir; do
    LINK_ARGS+=(--extra-packages "$pkg")
done < "$BUILD/aar-packages.txt"
for f in "$BUILD"/flat/*/*.flat; do
    LINK_ARGS+=(-R "$f")
done
"$AAPT2" link "${LINK_ARGS[@]}"

echo "== 3. javac R.java =="
find "$BUILD/gen" -name "R.java" > "$BUILD/rlist.txt"
# -encoding UTF-8: aapt2 writes UTF-8 curly quotes into R.java Javadoc
# (tint-mode docs); javac defaults to US-ASCII here and fails without it.
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -d "$BUILD/rclasses" @"$BUILD/rlist.txt"
(cd "$BUILD/rclasses" && "$JAVA_HOME/bin/jar" cf "$BUILD/r.jar" .)

echo "== 4. kotlinc (embeddable) + Compose plugin =="
D="$FILES_ROOT/deps"
K="$TOOLS/kotlinc/lib"
CP_JARS=$(paste -sd: "$BUILD/jars.txt")
CP="$CP_JARS:$BUILD/r.jar:$ANDROID_JAR"
COMPILER_CP="$TOOLS/kotlin-compiler-embeddable-2.0.21.jar:$D/kotlin-stdlib-2.0.21.jar:$TOOLS/trove4j-1.0.20200330.jar:$K/annotations-13.0.jar:$D/kotlinx-coroutines-core-jvm-1.7.3.jar:$K/kotlin-script-runtime.jar:$K/kotlin-reflect.jar"
find "$SRC/java" -name "*.kt" > "$BUILD/sources.txt"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 \
    -Xplugin="$TOOLS/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar" \
    -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 5. d8 dex =="
# d8 does not accept a bare directory of .class files; jar them first.
(cd "$BUILD/classes" && "$JAVA_HOME/bin/jar" cf "$BUILD/app-classes.jar" .)
# Dedupe classes across jars (d8 fails on duplicate classes).
# NOTE: dedupe_jars.py writes into its own project's build/ (it derives ROOT
# from its own path), so the Dialer keeps its own copy in tools/.
{ echo "$BUILD/app-classes.jar"; echo "$BUILD/r.jar"; cat "$BUILD/jars.txt"; } > "$BUILD/all-jars.txt"
python3 "$ROOT/tools/dedupe_jars.py" "$BUILD/all-jars.txt"
mapfile -t DEX_INPUTS < "$BUILD/dex-jars.txt"
"$D8" --min-api 29 --output "$BUILD/dex" --lib "$ANDROID_JAR" "${DEX_INPUTS[@]}"

echo "== 6. add dex to apk, align, sign =="
python3 - "$BUILD/apk/app-unsigned.apk" "$BUILD/dex" <<'PY'
import sys, zipfile, glob
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(dexdir + '/classes*.dex')):
        z.write(dex, dex.split('/')[-1])
print("dex added")
PY
"$ZIPALIGN" -f 4 "$BUILD/apk/app-unsigned.apk" "$BUILD/apk/app-aligned.apk"
if [ ! -f "$ROOT/debug.keystore" ]; then
    "$KEYTOOL" -genkeypair -keystore "$ROOT/debug.keystore" -storepass android \
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
"$APKSIGNER" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$BUILD/apk/nova-gallery-debug.apk" "$BUILD/apk/app-aligned.apk"
"$APKSIGNER" verify "$BUILD/apk/nova-gallery-debug.apk"
echo "== APK built: $BUILD/apk/nova-gallery-debug.apk =="
ls -lh "$BUILD/apk/nova-gallery-debug.apk"
"$AAPT2" dump badging "$BUILD/apk/nova-gallery-debug.apk" | head -8
