# Rough detokenizer for Godot 3.x GDScript bytecode v13 (.gdc) -> readable source
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gdc_dump import load
TK = ("EMPTY IDENTIFIER CONSTANT self BUILT_IN_TYPE BUILT_IN_FUNC in == != < <= > >= and or not + - * / % << >> = += -= *= /= %= <<= >>= &= |= ^= & | ^ ~ "
      "if elif else for while break continue pass return match func class class_name extends is onready tool static export setget const var as void enum preload assert yield signal breakpoint "
      "remote sync master slave puppet remotesync mastersync puppetsync [ ] { } ( ) , ; . ? : $ -> NEWLINE PI TAU _ INF NAN ERROR EOF CURSOR").split()
TYPES = "Nil bool int float String Vector2 Rect2 Vector3 Transform2D Plane Quat AABB Basis Transform Color NodePath RID Object Dictionary Array PoolByteArray PoolIntArray PoolRealArray PoolStringArray PoolVector2Array PoolVector3Array PoolColorArray".split()
FUNCS = ("sin cos tan sinh cosh tanh asin acos atan atan2 sqrt fmod fposmod posmod floor ceil round abs sign pow log exp is_nan is_inf is_equal_approx is_zero_approx ease decimals step_decimals stepify lerp lerp_angle inverse_lerp range_lerp smoothstep move_toward dectime randomize randi randf rand_range seed rand_seed deg2rad rad2deg linear2db db2linear polar2cartesian cartesian2polar wrapi wrapf max min clamp nearest_po2 weakref funcref convert typeof type_exists char ord str print printt prints printerr printraw print_debug push_error push_warning var2str str2var var2bytes bytes2var range load inst2dict dict2inst validate_json parse_json to_json hash Color8 ColorN print_stack get_stack instance_from_id len is_instance_valid deep_equal").split()
def const_repr(c):
    if isinstance(c, str): return '"' + c.replace(chr(92), chr(92) * 2).replace('"', chr(92) + '"').replace(chr(10), chr(92) + 'n') + '"'
    if c is None: return 'null'
    if isinstance(c, bool): return 'true' if c else 'false'
    if isinstance(c, tuple): return '%s(%s)' % (c[0], ', '.join('%g' % x for x in c[1:]))
    return repr(c)
def decomp(path):
    ver, ids, consts, lines, toks = load(path)
    out = []; cur = ''
    NOSPACE_BEFORE = {')', ']', ',', '.', ':', '('}
    NOSPACE_AFTER = {'(', '[', '.', '$', '~'}
    prev = None
    for t in toks:
        kind = t & 0xff; arg = t >> 8
        name = TK[kind] if kind < len(TK) else '?%d' % kind
        if name == 'NEWLINE':
            out.append(cur); cur = '\t' * arg; prev = None; continue
        if name == 'EOF': break
        if name == 'IDENTIFIER': s = ids[arg]
        elif name == 'CONSTANT': s = const_repr(consts[arg])
        elif name == 'BUILT_IN_TYPE': s = TYPES[arg] if arg < len(TYPES) else '?type%d' % arg
        elif name == 'BUILT_IN_FUNC': s = FUNCS[arg] if arg < len(FUNCS) else '?func%d' % arg
        else: s = name
        if prev is not None and s not in NOSPACE_BEFORE and prev not in NOSPACE_AFTER:
            cur += ' '
        elif s == '(' and prev in ('if', 'elif', 'while', 'and', 'or', 'not', 'return', '=', '==', 'in', ',', '+', '-', '*', '/'):
            cur += ' '
        cur += s; prev = s
    out.append(cur)
    return '\n'.join(l for l in out if l.strip())
if __name__ == '__main__':
    src = sys.argv[1]
    if os.path.isdir(src):
        dst = sys.argv[2]; n = 0
        for root, _, files in os.walk(src):
            for f in files:
                if f.endswith('.gdc'):
                    p = os.path.join(root, f); rel = os.path.relpath(p, src)[:-1]
                    o = os.path.join(dst, rel); os.makedirs(os.path.dirname(o), exist_ok=True)
                    try:
                        open(o, 'w', encoding='utf-8').write(decomp(p)); n += 1
                    except Exception as e:
                        print('FAIL', rel, e)
        print('decompiled', n)
    else:
        print(decomp(src))
