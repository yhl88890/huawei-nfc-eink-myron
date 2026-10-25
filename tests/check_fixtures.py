"""Independent native LZO decompression and reference-source packing cross-check.
Run with lzokay installed; reference checkout is optional for reproducible tests.
"""
import ast
import struct
import sys
from pathlib import Path
import lzokay

root = Path(sys.argv[1])
blob = (root / 'lzo-fixtures.bin').read_bytes()
offset = count = 0
while offset < len(blob):
    size, packed_size = struct.unpack_from('>HH', blob, offset)
    offset += 4
    compressed = blob[offset:offset + packed_size]
    offset += packed_size
    original = blob[offset:offset + size]
    offset += size
    assert lzokay.decompress(compressed, size) == original, size
    count += 1
assert offset == len(blob) and count == 2000
print('PASS independent lzokay decompression for all raw sizes 1..2000')

reference = Path(__file__).resolve().parents[2] / 'sources/huawei-epd-pn532/image_codec.py'
if reference.exists():
    # Execute only the pure reference packing function, without its image dependencies.
    tree = ast.parse(reference.read_text(encoding='utf-8'))
    function = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'pack_codes')
    scope = {}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(reference), 'exec'), scope)
    assert scope['pack_codes']((root / 'codes.bin').read_bytes()) == (root / 'packed.bin').read_bytes()
    print('PASS Java 552x768 packing equals pinned Gitee reference for seeded random image')
else:
    print('SKIP reference source cross-check: checkout absent; Java direction vector still tested')
