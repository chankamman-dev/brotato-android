"""Patch the apktool-decoded shell manifest for the Steam bridge (idempotent).

usage: py -3.11 -I patch_manifest.py <decoded-dir>

- registers the Godot 3 plugin: <meta-data org.godotengine.plugin.v1.Steam = dev.kmbrotato.steam.SteamPlugin>
- adds dev.kmbrotato.steam.LauncherActivity as the launcher entry (sign-in / cloud sync, then GodotApp);
  GodotApp loses its MAIN/LAUNCHER filter but stays exported so adb can start it directly
- rewrites <uses-permission xmlns:n0="android" n0:name=...>, which apktool 3.0.2 decodes from the
  Godot exporter's binary manifest with the bare prefix as namespace, back to android:name
- raises minSdkVersion in apktool.yml to 26 (JavaSteam, okhttp, ktor)
"""
import os
import re
import sys

d = sys.argv[1]
mf = os.path.join(d, 'AndroidManifest.xml')
s = open(mf, encoding='utf-8').read()

s = re.sub(r'<uses-permission xmlns:(n\d+)="android" \1:name="([^"]+)"/>', r'<uses-permission android:name="\2"/>', s)

godot = re.search(r'<activity [^>]*android:name="com\.godot\.game\.GodotApp"[^>]*>.*?</activity>', s, re.S)
if not godot:
    raise SystemExit('GodotApp activity not found')
block = godot.group(0)
new_block = re.sub(r'\s*<intent-filter>.*?</intent-filter>', '', block, flags=re.S)
s = s.replace(block, new_block)

if 'dev.kmbrotato.steam.LauncherActivity' not in s:
    launcher = (
        '\n        <activity android:name="dev.kmbrotato.steam.LauncherActivity" android:exported="true"'
        ' android:label="@string/godot_project_name_string"'
        ' android:theme="@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen"'
        ' android:screenOrientation="userLandscape"'
        ' android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize">'
        '\n            <intent-filter>'
        '\n                <action android:name="android.intent.action.MAIN"/>'
        '\n                <category android:name="android.intent.category.LAUNCHER"/>'
        '\n            </intent-filter>'
        '\n        </activity>'
        '\n        <meta-data android:name="org.godotengine.plugin.v1.Steam" android:value="dev.kmbrotato.steam.SteamPlugin"/>'
    )
    s = s.replace(new_block, new_block + launcher, 1)

open(mf, 'w', encoding='utf-8').write(s)

yml = os.path.join(d, 'apktool.yml')
y = open(yml, encoding='utf-8').read()
y = re.sub(r'minSdkVersion: \d+', 'minSdkVersion: 26', y)
open(yml, 'w', encoding='utf-8').write(y)
print('patched', mf)
