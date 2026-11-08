import struct, sys, collections, json
p = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else None
with open(p, 'rb') as f:
    magic = f.read(4); assert magic == b'GDPC', magic
    fmt, ma, mi, pa = struct.unpack('<4I', f.read(16))
    f.read(64)
    n, = struct.unpack('<I', f.read(4))
    files = []
    for i in range(n):
        l, = struct.unpack('<I', f.read(4))
        path = f.read(l).rstrip(b'\0').decode('utf-8')
        off, size = struct.unpack('<QQ', f.read(16))
        md5 = f.read(16).hex()
        files.append((path, off, size))
print('format', fmt, 'version', ma, mi, pa, 'files', n)
ext = collections.Counter()
for path, off, size in files:
    e = path.rsplit('.', 1)[-1] if '.' in path.rsplit('/', 1)[-1] else ''
    ext[e] += 1
print(ext.most_common(40))
if out:
    with open(out, 'w', encoding='utf-8') as o:
        json.dump(files, o)
