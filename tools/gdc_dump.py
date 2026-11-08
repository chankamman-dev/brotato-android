# Dump identifiers and constants of a Godot 3.x (bytecode v13) .gdc file
import struct, sys
def rd(b, o, fmt):
    s = struct.calcsize(fmt); return struct.unpack_from(fmt, b, o), o + s
def variant(b, o):
    (t,), o = rd(b, o, '<I'); typ = t & 0xffff; f64 = t & (1 << 16)
    if typ == 0: return None, o
    if typ == 1: (v,), o = rd(b, o, '<I'); return bool(v), o
    if typ == 2:
        if f64: (v,), o = rd(b, o, '<q')
        else: (v,), o = rd(b, o, '<i')
        return v, o
    if typ == 3:
        if f64: (v,), o = rd(b, o, '<d')
        else: (v,), o = rd(b, o, '<f')
        return v, o
    if typ == 4:
        (l,), o = rd(b, o, '<I'); s = b[o:o+l].decode('utf-8', 'replace'); o += l; o += (4 - l % 4) % 4
        return s, o
    if typ == 5: v, o = rd(b, o, '<2f'); return ('Vector2',) + v, o
    if typ == 6: v, o = rd(b, o, '<4f'); return ('Rect2',) + v, o
    if typ == 7: v, o = rd(b, o, '<3f'); return ('Vector3',) + v, o
    if typ == 14: v, o = rd(b, o, '<4f'); return ('Color',) + v, o
    raise ValueError('variant type %d at %d' % (typ, o))
def load(path):
    b = open(path, 'rb').read()
    assert b[:4] == b'GDSC'
    (ver, ni, nc, nl, nt), o = rd(b, 4, '<5I')
    ids = []
    for i in range(ni):
        (l,), o = rd(b, o, '<I')
        raw = bytes(x ^ 0xb6 for x in b[o:o+l]); o += l
        ids.append(raw.split(b'\0')[0].decode('utf-8', 'replace'))
    consts = []
    for i in range(nc):
        v, o = variant(b, o); consts.append(v)
    lines = {}
    for i in range(nl):
        (tok, line), o = rd(b, o, '<2I'); lines[tok] = line
    toks = []
    for i in range(nt):
        if b[o] & 0x80:
            (v,), o = rd(b, o, '<I'); v &= ~0x80
        else:
            v = b[o]; o += 1
        toks.append(v)
    return ver, ids, consts, lines, toks
if __name__ == '__main__':
    ver, ids, consts, lines, toks = load(sys.argv[1])
    print('version', ver, 'ids', len(ids), 'consts', len(consts), 'tokens', len(toks))
    print('IDS:', ' '.join(ids))
    print('CONSTS:', [c for c in consts if isinstance(c, str)])
