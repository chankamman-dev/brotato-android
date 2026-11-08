"""Build the Brotato APK from the Godot 3.6.2 shell export plus the PC game's Brotato.pck.

usage: py -3.11 -I assemble_apk.py <shell.apk> <Brotato.pck> <out-unsigned.apk> [--dex <dir>]
                                  [--external <device pck path> --android <dir>] [overlay-dir ...]

- Everything from the shell APK is kept except its assets/ (the shell project's files) and the
  v1 signature files; assets/_cl_ (the Godot command line written by the exporter) is kept.
- Every file of the pck is written to assets/<path without res://>, which is how the official
  Godot 3 Android exporter lays out a project.
- Each overlay directory is copied on top (res:// relative paths), later ones win. An empty file
  named <path>.delete removes <path>.
- --dex: the classes*.dex in <dir> (the Steam bridge with JavaSteam) become classes.dex.. and the
  shell's own dex files are renumbered after them. Android searches the dex files in order, so the
  newer Kotlin stdlib that JavaSteam/ktor need shadows the older copy bundled with Godot.
- --external: the APK carries no game files. The pck (from Steam, or the player's own copy) is
  the main pack at <device pck path>: `--main-pack <path>` is appended to assets/_cl_. <dir>/boot.gd
  goes to assets/android/boot.gd (the launcher's override.cfg makes it the first autoload), and the
  overlay directories are packed into assets/android/overlay.zip, which boot.gd mounts over the game
  with replace_files. In that zip an overlay file `<path>.remap.delete` becomes a `<path>.remap`
  that points at <path> itself, so the overlay's text script wins over the game's .gdc.
  <Brotato.pck> is then only read for its header.
The result still has to be zipaligned and signed (see build.sh).
"""
import io
import os
import re
import struct
import sys
import zipfile

STORE_EXT = {'.stex', '.mp3str', '.oggstr', '.png', '.jpg', '.ogg', '.mp3', '.webp'}


def dex_index(name):
    m = re.fullmatch(r'classes(\d*)\.dex', name)
    return int(m.group(1) or 1)


def dex_name(i):
    return 'classes.dex' if i == 1 else 'classes%d.dex' % i


def read_pck(path):
    with open(path, 'rb') as f:
        if f.read(4) != b'GDPC':
            raise SystemExit('not a Godot pck: ' + path)
        fmt, major, minor, patch = struct.unpack('<4I', f.read(16))
        if fmt != 1:
            raise SystemExit('unsupported pck format %d' % fmt)
        f.read(64)
        (count,) = struct.unpack('<I', f.read(4))
        entries = []
        for _ in range(count):
            (n,) = struct.unpack('<I', f.read(4))
            name = f.read(n).rstrip(b'\0').decode('utf-8')
            off, size = struct.unpack('<QQ', f.read(16))
            f.read(16)  # md5
            entries.append((name, off, size))
    return (major, minor, patch), entries


def main():
    shell, pck, out = sys.argv[1:4]
    rest = sys.argv[4:]
    dex_dir = None
    if '--dex' in rest:
        i = rest.index('--dex')
        dex_dir = rest[i + 1]
        del rest[i:i + 2]
    external = android_dir = None
    if '--external' in rest:
        i = rest.index('--external')
        external = rest[i + 1]
        del rest[i:i + 2]
    if '--android' in rest:
        i = rest.index('--android')
        android_dir = rest[i + 1]
        del rest[i:i + 2]
    overlays = rest
    if external:
        return assemble_external(shell, pck, out, dex_dir, external, android_dir, overlays)
    extra_dex = []
    if dex_dir:
        extra_dex = sorted((f for f in os.listdir(dex_dir) if re.fullmatch(r'classes\d*\.dex', f)), key=dex_index)
    version, entries = read_pck(pck)
    print('pck engine %d.%d.%d, %d files' % (version + (len(entries),)))

    files = {}  # asset path -> (source kind, data)
    for name, off, size in entries:
        rel = name[len('res://'):] if name.startswith('res://') else name
        files[rel] = ('pck', off, size)
    for ov in overlays:
        for root, _, names in os.walk(ov):
            for n in names:
                full = os.path.join(root, n)
                rel = os.path.relpath(full, ov).replace(os.sep, '/')
                if rel.endswith('.delete'):
                    files.pop(rel[:-len('.delete')], None)
                else:
                    files[rel] = ('file', full, os.path.getsize(full))

    with zipfile.ZipFile(shell) as zin, zipfile.ZipFile(out, 'w') as zout, open(pck, 'rb') as fp:
        shell_dex = sorted((i.filename for i in zin.infolist() if re.fullmatch(r'classes\d*\.dex', i.filename)), key=dex_index)
        renamed = {name: dex_name(len(extra_dex) + k + 1) for k, name in enumerate(shell_dex)}
        for k, name in enumerate(extra_dex):
            zi = zipfile.ZipInfo(dex_name(k + 1), date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(dex_dir, name), 'rb') as f:
                zout.writestr(zi, f.read())
        for info in zin.infolist():
            n = info.filename
            if n in renamed and extra_dex:
                zi = zipfile.ZipInfo(renamed[n], date_time=info.date_time)
                zi.compress_type = info.compress_type
                zout.writestr(zi, zin.read(info))
                continue
            if n.startswith('assets/') and n != 'assets/_cl_':
                continue
            if n.startswith('META-INF/') and n.rsplit('.', 1)[-1].upper() in ('SF', 'RSA', 'DSA', 'EC', 'MF'):
                continue
            zout.writestr(info, zin.read(info))
        for rel in sorted(files):
            kind, a, size = files[rel]
            if kind == 'pck':
                fp.seek(a)
                data = fp.read(size)
            else:
                with open(a, 'rb') as f:
                    data = f.read()
            ext = os.path.splitext(rel)[1].lower()
            ctype = zipfile.ZIP_STORED if ext in STORE_EXT else zipfile.ZIP_DEFLATED
            zi = zipfile.ZipInfo('assets/' + rel, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = ctype
            zout.writestr(zi, data)
    print('wrote', out, len(files), 'assets', ('+ %d bridge dex' % len(extra_dex)) if extra_dex else '')


def read_cl(data):
    (n,) = struct.unpack_from('<i', data, 0)
    args, pos = [], 4
    for _ in range(n):
        (l,) = struct.unpack_from('<i', data, pos)
        pos += 4
        args.append(data[pos:pos + l].decode('utf-8'))
        pos += l
    return args


def write_cl(args):
    out = struct.pack('<i', len(args))
    for a in args:
        b = a.encode('utf-8')
        out += struct.pack('<i', len(b)) + b
    return out


def overlay_zip(overlays):
    buf = io.BytesIO()
    entries = {}
    for ov in overlays:
        for root, _, names in os.walk(ov):
            for n in names:
                full = os.path.join(root, n)
                rel = os.path.relpath(full, ov).replace(os.sep, '/')
                if rel.endswith('.remap.delete'):
                    target = rel[:-len('.remap.delete')]
                    entries[target + '.remap'] = ('[remap]\n\npath="res://%s"\n' % target).encode('utf-8')
                elif rel.endswith('.delete'):
                    raise SystemExit('cannot delete game files in --external mode: ' + rel)
                else:
                    with open(full, 'rb') as f:
                        entries[rel] = f.read()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as z:
        for rel in sorted(entries):
            zi = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            z.writestr(zi, entries[rel])
    return buf.getvalue(), sorted(entries)


def assemble_external(shell, pck, out, dex_dir, device_pck, android_dir, overlays):
    if not android_dir:
        raise SystemExit('--external needs --android <dir>')
    version, entries = read_pck(pck)
    print('pck engine %d.%d.%d, %d files (not bundled)' % (version + (len(entries),)))
    extra_dex = []
    if dex_dir:
        extra_dex = sorted((f for f in os.listdir(dex_dir) if re.fullmatch(r'classes\d*\.dex', f)), key=dex_index)
    ozip, onames = overlay_zip(overlays)
    print('overlay.zip:', ', '.join(onames))
    with open(os.path.join(android_dir, 'boot.gd'), 'rb') as f:
        boot = f.read()
    with zipfile.ZipFile(shell) as zin, zipfile.ZipFile(out, 'w') as zout:
        shell_dex = sorted((i.filename for i in zin.infolist() if re.fullmatch(r'classes\d*\.dex', i.filename)), key=dex_index)
        renamed = {name: dex_name(len(extra_dex) + k + 1) for k, name in enumerate(shell_dex)}
        for k, name in enumerate(extra_dex):
            zi = zipfile.ZipInfo(dex_name(k + 1), date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(dex_dir, name), 'rb') as f:
                zout.writestr(zi, f.read())
        cl = None
        for info in zin.infolist():
            n = info.filename
            if n in renamed and extra_dex:
                zi = zipfile.ZipInfo(renamed[n], date_time=info.date_time)
                zi.compress_type = info.compress_type
                zout.writestr(zi, zin.read(info))
                continue
            if n == 'assets/_cl_':
                cl = read_cl(zin.read(info))
                continue
            if n.startswith('assets/'):
                continue
            if n.startswith('META-INF/') and n.rsplit('.', 1)[-1].upper() in ('SF', 'RSA', 'DSA', 'EC', 'MF'):
                continue
            zout.writestr(info, zin.read(info))
        if cl is None:
            raise SystemExit('shell has no assets/_cl_')
        if '--main-pack' in cl:
            raise SystemExit('shell _cl_ already has --main-pack')
        cl += ['--main-pack', device_pck]
        print('_cl_:', cl)
        for name, data in (('assets/_cl_', write_cl(cl)), ('assets/android/boot.gd', boot), ('assets/android/overlay.zip', ozip)):
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if name.endswith('.zip') else zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)
    print('wrote', out, '(game not bundled)', ('+ %d bridge dex' % len(extra_dex)) if extra_dex else '')


if __name__ == '__main__':
    main()
