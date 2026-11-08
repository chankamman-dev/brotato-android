#!/usr/bin/env bash
# Turns the exporter's shell APK into the Steam shell: apktool decodes it (keeping resource ids via
# public.xml), patch_manifest.py adds the plugin meta-data and the launcher activity, apktool rebuilds.
# usage: bash tools/make-shell-steam.sh [debug|release]   (default release; run export-shell.ps1 first)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$HERE/local.env" ] && . "$HERE/local.env"  # machine paths, see tools/local.env.example
: "${BROTATO_WORK:?set BROTATO_WORK in tools/local.env (see tools/local.env.example)}"
: "${JDK_HOME:?set JDK_HOME in tools/local.env (see tools/local.env.example)}"
: "${APKTOOL_JAR:?set APKTOOL_JAR in tools/local.env (see tools/local.env.example)}"
WORK="$BROTATO_WORK"
KIND="${1:-release}"
JAVA="$JDK_HOME/bin/java"
# apktool 3.0.2, already on this machine from an earlier project (not downloaded for this one).
APKTOOL="$APKTOOL_JAR"
DEC="$WORK/shell-decoded-$KIND"
rm -rf "$DEC"
"$JAVA" -jar "$APKTOOL" d -s -f -o "$(cygpath -w "$DEC")" "$(cygpath -w "$WORK/out/shell-$KIND.apk")"
py -3.11 -I "$(cygpath -w "$HERE/patch_manifest.py")" "$(cygpath -w "$DEC")"
"$JAVA" -jar "$APKTOOL" b -f -o "$(cygpath -w "$WORK/out/shell-steam-$KIND.apk")" "$(cygpath -w "$DEC")"
ls -la "$WORK/out/shell-steam-$KIND.apk"
