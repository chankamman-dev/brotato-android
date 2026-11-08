# Export the shell project with the official Godot 3.6.2 Android exporter (hidden, no window).
# Produces E:\android-recompile-work\brotato-android-work\out\shell-debug.apk and shell-release.apk; build.sh swaps in Brotato.pck.
# The shell project only supplies the manifest (package, label, icon, permissions, orientation) and assets/_cl_.
$ErrorActionPreference = 'Stop'
$godot = 'E:\android-recompile-work\brotato-android-work\godot362\Godot_v3.6.2-stable_win64.exe'
$project = Join-Path (Split-Path $PSScriptRoot -Parent) 'shell'
$out = 'E:\android-recompile-work\brotato-android-work\out'
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($kind in @('debug', 'release')) {
    $flag = if ($kind -eq 'debug') { '--export-debug' } else { '--export' }
    $apk = Join-Path $out "shell-$kind.apk"
    $argList = "--no-window --path `"$project`" $flag `"Android`" `"$apk`""
    $p = Start-Process -FilePath $godot -ArgumentList $argList -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $out "export-$kind.log") -RedirectStandardError (Join-Path $out "export-$kind-err.log")
    if (-not $p.WaitForExit(300000)) { $p.Kill(); throw "export $kind timed out" }
    if (-not (Test-Path $apk)) { Get-Content (Join-Path $out "export-$kind-err.log") -Tail 20; throw "export $kind failed" }
    "{0}: {1:N0} bytes" -f $apk, (Get-Item $apk).Length
}
