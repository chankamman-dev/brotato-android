import struct, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gdc_dump import variant
b = open(sys.argv[1], 'rb').read()
assert b[:4] == b'ECFG'
n, = struct.unpack_from('<I', b, 4); o = 8
for i in range(n):
    l, = struct.unpack_from('<I', b, o); o += 4
    k = b[o:o+l].decode(); o += l
    vl, = struct.unpack_from('<I', b, o); o += 4
    try:
        v, _ = variant(b, o)
    except Exception as e:
        v = '<%d bytes, %s>' % (vl, e)
    o += vl
    if k.startswith('_global_script_class'):
        v = '<omitted>'
    print(k, '=', repr(v)[:300])
