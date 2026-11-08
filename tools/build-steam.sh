#!/usr/bin/env bash
# Compiles the Steam bridge (steam/src) and dexes it with JavaSteam and its dependencies.
# Output: $WORK/steam/dex/classes*.dex, which build.sh puts in front of the engine's dex files so the
# newer Kotlin stdlib that ktor/JavaSteam need wins over the one bundled with Godot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
[ -f "$HERE/local.env" ] && . "$HERE/local.env"  # machine paths, see tools/local.env.example
: "${BROTATO_WORK:?set BROTATO_WORK in tools/local.env (see tools/local.env.example)}"
: "${ANDROID_SDK:?set ANDROID_SDK in tools/local.env (see tools/local.env.example)}"
: "${JDK_HOME:?set JDK_HOME in tools/local.env (see tools/local.env.example)}"
WORK="$BROTATO_WORK"
SDK="$ANDROID_SDK"
BT="$SDK/build-tools/36.0.0"   # 34's d8 crashes on commons-lang3 (see lom-android HANDOFF)
JAR="$SDK/platforms/android-35/android.jar"
JDK="$JDK_HOME/bin"
GODOT_CLASSES="$WORK/godot-src/aar/classes.jar"
# JavaSteam 1.8.0 + bouncycastle + zxing + protobuf + okhttp and their runtime deps, as collected by
# the Legend of Mortal port (lom-android/cloud: gradle collectRuntimeDeps). Copied, not downloaded.
DEPS="$WORK/steam/deps"
if [ ! -d "$DEPS" ]; then
  mkdir -p "$DEPS"
  [ -n "${JAVASTEAM_DEPS:-}" ] || { echo "no $DEPS: set JAVASTEAM_DEPS in tools/local.env"; exit 1; }
  cp "$JAVASTEAM_DEPS/"*.jar "$DEPS/"
fi
[ -f "$GODOT_CLASSES" ] || { mkdir -p "$WORK/godot-src" && (cd "$WORK/godot-src" && unzip -o -q "$WORK/godot362/editor_data/templates/3.6.2.stable/android_source.zip" && mkdir -p aar && cd aar && unzip -o -q ../libs/release/godot-lib.release.aar); }

CP="$(cygpath -w "$JAR");$(cygpath -w "$GODOT_CLASSES")"
for j in "$DEPS"/*.jar; do CP="$CP;$(cygpath -w "$j")"; done
rm -rf "$WORK/steam/classes" "$WORK/steam/dex"
mkdir -p "$WORK/steam/classes" "$WORK/steam/dex"
# Source paths can contain spaces: pass them through an argument file, one quoted path per line.
find "$ROOT/steam/src" -name '*.java' -exec cygpath -w {} \; | sed 's/\\/\\\\/g; s/.*/"&"/' > "$WORK/steam/sources.txt"
"$JDK/javac" -nowarn -encoding UTF-8 --release 17 -cp "$CP" -d "$(cygpath -w "$WORK/steam/classes")" \
  "@$(cygpath -w "$WORK/steam/sources.txt")"
(cd "$WORK/steam/classes" && "$JDK/jar" cf ../brotato-steam.jar .)

INPUTS=("$(cygpath -w "$WORK/steam/brotato-steam.jar")")
for j in "$DEPS"/*.jar; do INPUTS+=("$(cygpath -w "$j")"); done
"$JDK/java" -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$(cygpath -w "$JAR")" --classpath "$(cygpath -w "$GODOT_CLASSES")" \
  --output "$(cygpath -w "$WORK/steam/dex")" "${INPUTS[@]}" 2>&1 | grep -vE '^Warning|Missing class|^\s*$' | head -20 || true
ls -la "$WORK/steam/dex"
