#!/usr/bin/env bash
# Build the Brotato Android APK: Steam shell (Godot 3.6.2 export + plugin manifest) + Steam bridge dex
# + overlays, then align and sign. By default the game is not bundled: the launcher downloads
# Brotato.pck from Steam (or imports the player's copy) into files/game/ and Godot runs it as the main
# pack (about 25 MB APK). BUNDLE=1 puts the PC pck's files into assets/ as before (about 290 MB).
# usage: bash tools/build.sh [out.apk]
# Prerequisites: tools/export-shell.ps1, tools/make-shell-steam.sh <kind>, tools/build-steam.sh
# STEAM=0 builds without the bridge from the plain shell (no launcher, offline only).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
[ -f "$HERE/local.env" ] && . "$HERE/local.env"  # machine paths, see tools/local.env.example
: "${BROTATO_WORK:?set BROTATO_WORK in tools/local.env (see tools/local.env.example)}"
: "${GAME_DIR:?set GAME_DIR in tools/local.env (see tools/local.env.example)}"
: "${BUILD_TOOLS:?set BUILD_TOOLS in tools/local.env (see tools/local.env.example)}"
: "${JDK_HOME:?set JDK_HOME in tools/local.env (see tools/local.env.example)}"
WORK="$BROTATO_WORK"
GAME="$GAME_DIR"
BT="$BUILD_TOOLS"
JAVA_HOME="$JDK_HOME"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
# KIND=debug uses the debug template (verbose engine errors, but the game's debug menu is reachable).
KIND="${KIND:-release}"
OUT="${1:-$WORK/out/Brotato-android-$KIND.apk}"
STEAM="${STEAM:-1}"
BUNDLE="${BUNDLE:-0}"
DEVICE_PCK="${DEVICE_PCK:-user://game/Brotato.pck}"  # user:// is read natively; /data/... goes through Java on Godot 3.6 Android
if [ "$STEAM" = 1 ]; then
  SHELL_APK="$WORK/out/shell-steam-$KIND.apk"
  [ -f "$SHELL_APK" ] || { echo "missing $SHELL_APK (run tools/make-shell-steam.sh $KIND)"; exit 1; }
  ls "$WORK/steam/dex/classes.dex" >/dev/null || { echo "missing bridge dex (run tools/build-steam.sh)"; exit 1; }
  dexargs=(--dex "$(cygpath -w "$WORK/steam/dex")")
else
  SHELL_APK="$WORK/out/shell-$KIND.apk"
  [ -f "$SHELL_APK" ] || { echo "missing $SHELL_APK (run tools/export-shell.ps1)"; exit 1; }
  dexargs=()
fi

overlays=()
[ -d "$ROOT/overlay" ] && overlays+=("$(cygpath -w "$ROOT/overlay")")
extargs=()
if [ "$BUNDLE" != 1 ]; then
  [ "$STEAM" = 1 ] || { echo "the unbundled APK needs the Steam launcher (STEAM=1) or BUNDLE=1"; exit 1; }
  extargs=(--external "$DEVICE_PCK" --android "$(cygpath -w "$ROOT/android")")
fi

MSYS_NO_PATHCONV=1 py -3.11 -I "$(cygpath -w "$HERE/assemble_apk.py")" "$(cygpath -w "$SHELL_APK")" \
  "$(cygpath -w "$GAME/Brotato.pck")" "$(cygpath -w "$WORK/out/unsigned.apk")" "${dexargs[@]}" "${extargs[@]}" "${overlays[@]}"
"$BT/zipalign.exe" -p -f 4 "$WORK/out/unsigned.apk" "$WORK/out/aligned.apk"
PW="$(tr -d '\r\n' < "$ROOT/signing/password.txt")"
java -jar "$(cygpath -w "$BT/lib/apksigner.jar")" sign --ks "$(cygpath -w "$ROOT/signing/brotato.keystore")" --ks-key-alias brotato \
  --ks-pass "pass:$PW" --key-pass "pass:$PW" --out "$(cygpath -w "$OUT")" "$(cygpath -w "$WORK/out/aligned.apk")"
rm -f "$WORK/out/unsigned.apk" "$WORK/out/aligned.apk"
ls -la "$OUT"
sha256sum "$OUT"
