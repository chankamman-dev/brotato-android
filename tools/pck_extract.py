import json, sys, os, re
pck, listing, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
pat = re.compile(sys.argv[4]) if len(sys.argv) > 4 else None
files = json.load(open(listing, encoding='utf-8'))
cnt = 0
with open(pck, 'rb') as f:
    for path, off, size in files:
        if pat and not pat.search(path):
            continue
        rel = path.replace('res://', '')
        dst = os.path.join(outdir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        f.seek(off)
        with open(dst, 'wb') as o:
            remaining = size
            while remaining:
                b = f.read(min(remaining, 1 << 20)); o.write(b); remaining -= len(b)
        cnt += 1
print('extracted', cnt)
