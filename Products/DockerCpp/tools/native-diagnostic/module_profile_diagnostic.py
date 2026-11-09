#!/usr/bin/env python3
"""受控001/type0或type3/LZMA模块诊断。仅解析数据，从不执行ARM代码。

ZUC结构移植自仓库Apache-2.0 cipher/algo/zuc.c；公开S盒/标准D表从该源码读取，
不复制原生库密码参数。原生loader固定参数从已识别二进制literal读取。
"""
import argparse
import hashlib
import json
import lzma
import re
import struct
from pathlib import Path

NATIVE_SHA = '69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c'
TABLE_SHA = 'dd5cd2b9db874a36388a3393f9362519dd4f43c3c164c03f62cf4b42707dc095'
PROFILE = 'android32-2082-module-masked-image-v1'
MASKED_IMAGE_SHA = '5d0d38e512f0c25f8f6423d2f66754f28e2af78cc9826b7776f2eff5d5a7e7d7'
SECTIONS = {
    '.text': (0x1d1c, 25688, '6d5e0725f87e5e14dc5270b50096b9e684dec50c3f8dc2e6c0001286db50e865'),
    '.rodata': (0x8434, 686, 'b0a25256ae24882f076c66547bf2e25395bf1212879b948873f1f40585e5d6d7'),
    '.plt': (0x18dc, 1088, 'ad7487816e4a7c256ca888ff1a8cc8e0124aafdec958e9d013e187fb4f0adf91'),
}
MAX_INPUT = 1024 * 1024
PROFILES = (
    dict(name=PROFILE, sections=SECTIONS, masked_sha=MASKED_IMAGE_SHA,
         references=(0x31e4, 0x3206, 0x32f0, 0x3324), key_bytes=48, iv_bytes=8,
         algorithm='three-layer modified-TEA CBC', block_bytes=8,
         padding='NUL then zero pad', rounds_per_layer=32),
    dict(name='android32-2082-sm4-masked-image-v1', sections={
        '.text': (0x1c40, 24896, 'b1a8487cc9cb110e467ac00fe2c58b368dae385fdf31060b3e00e2f7f8912080'),
        '.rodata': (0x8018, 1110, 'ab5c56e00c818c27c3717a76da4612918a1f26896ad56867500504e63e00090d'),
        '.plt': (0x1830, 1040, '1340462ea50148d2d7d17a41a2ec680222987543bc4c5099cdbfaeadd8afdcdc'),
    }, masked_sha='c75879a06b55fe814cb280cf911d59dd612779463778e32e9bad031a70c1a545',
         references=(0x38c4, 0x38e6, 0x39d0, 0x3a04), key_bytes=16, iv_bytes=16,
         algorithm='SM4 CBC', block_bytes=16,
         padding='NUL then PKCS7'),
)
MAX_UNPACKED = 1024 * 1024
MAX_DICT = 16 * 1024 * 1024


def require(ok, code):
    if not ok:
        raise ValueError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Elf32:
    def __init__(self, data):
        self.data = data
        require(52 <= len(data) <= MAX_UNPACKED, 'ELF_SIZE_REJECTED')
        h = struct.unpack_from('<16sHHIIIIIHHHHHH', data)
        require(h[0][:7] == b'\x7fELF\x01\x01\x01', 'ELF_CLASS_REJECTED')
        require(h[1] == 3 and h[2] == 40 and h[8] == 52, 'ELF_ABI_REJECTED')
        require(h[9] == 32 and 0 < h[10] <= 64, 'ELF_PROGRAM_HEADERS_REJECTED')
        require(h[11] == 40 and 0 < h[12] <= 256 and h[13] < h[12], 'ELF_SECTIONS_REJECTED')
        self.segments = []
        for i in range(h[10]):
            raw = self.file(h[5] + i * 32, 32)
            typ, off, va, _, size, mem, flags, _ = struct.unpack('<8I', raw)
            require(size <= mem, 'ELF_SEGMENT_SIZE_REJECTED')
            if typ == 1:
                self.file(off, size)
                self.segments.append((va, off, size, mem, flags))
        headers = [struct.unpack('<10I', self.file(h[6] + i * 40, 40)) for i in range(h[12])]
        names = self.file(headers[h[13]][4], headers[h[13]][5])
        self.sections = {}
        for item in headers:
            require(item[0] < len(names), 'ELF_SECTION_NAME_REJECTED')
            end = names.find(b'\0', item[0])
            require(end >= 0, 'ELF_SECTION_NAME_REJECTED')
            name = names[item[0]:end].decode('ascii')
            if name:
                require(name not in self.sections, 'ELF_DUPLICATE_SECTION')
                self.sections[name] = item

    def file(self, off, size):
        require(0 <= off <= len(self.data) and 0 <= size <= len(self.data) - off, 'ELF_RANGE_REJECTED')
        return self.data[off:off + size]

    def at(self, va, size, writable=False):
        matches = [(a, o, n, f) for a, o, n, _, f in self.segments if a <= va and size <= a + n - va]
        require(len(matches) == 1, 'ELF_VIRTUAL_RANGE_REJECTED')
        a, off, _, flags = matches[0]
        if writable:
            require(flags & 2 and flags & 4 and not flags & 1, 'POOL_SEGMENT_REJECTED')
        return self.file(off + va - a, size)

    def file_offset(self, va, size):
        matches = [(a, o) for a, o, n, _, _ in self.segments if a <= va and size <= a + n - va]
        require(len(matches) == 1, 'ELF_VIRTUAL_RANGE_REJECTED')
        return matches[0][1] + va - matches[0][0]

    def pc_relative(self, instruction):
        load, add = struct.unpack('<2H', self.at(instruction, 4))
        require(load & 0xf800 == 0x4800, 'PROFILE_PC_LOAD_REJECTED')
        target_reg = (load >> 8) & 7
        require(add & 0xff00 == 0x4400 and (add >> 3) & 15 == 15, 'PROFILE_PC_ADD_REJECTED')
        destination = (add & 7) | ((add >> 4) & 8)
        require(destination == target_reg, 'PROFILE_PC_REGISTER_REJECTED')
        literal = ((instruction + 4) & ~3) + (load & 255) * 4
        delta = struct.unpack('<I', self.at(literal, 4))[0]
        return (instruction + 6 + delta) & 0xffffffff


def zuc_tables(source):
    text = source.read_text(encoding='utf-8')
    values = []
    for name, expected in [('ZUC_S0', 256), ('ZUC_S1', 256), ('ZUC_EK_D', 16)]:
        match = re.search(r'\b' + name + r'\s*\[[^]]*\]\s*=\s*\{([^}]+)\}', text)
        require(match is not None, 'ZUC_TABLES_MISSING')
        array = [int(x, 16) for x in re.findall(r'0x[0-9a-fA-F]+', match.group(1))]
        require(len(array) == expected, 'ZUC_TABLES_REJECTED')
        values.append(array)
    s0, s1, d = values
    require(sha(bytes(s0) + bytes(s1) + struct.pack('<16H', *d)) == TABLE_SHA, 'ZUC_TABLE_IDENTITY_REJECTED')
    return s0, s1, d


def zuc_process(key, iv, data, tables):
    s0, s1, d = tables
    mask = 0xffffffff
    lfsr = [(key[i] << 23 | d[i] << 8 | iv[i]) & 0x7fffffff for i in range(16)]
    r1 = r2 = 0
    def rot(x, n):
        return ((x << n) | (x >> (32 - n))) & mask
    def nonlinear(x, shifts):
        y = x
        for n in shifts:
            y ^= rot(x, n)
        return y & mask
    def sub(x):
        return s0[x >> 24] << 24 | s1[(x >> 16) & 255] << 16 | s0[(x >> 8) & 255] << 8 | s1[x & 255]
    def state_step(u=None):
        def add(a, b):
            c = (a + b) & mask
            return (c & 0x7fffffff) + (c >> 31)
        value = lfsr[0]
        for i, shift in [(0, 8), (4, 20), (10, 21), (13, 17), (15, 15)]:
            value = add(value, ((lfsr[i] << shift) | (lfsr[i] >> (31 - shift))) & 0x7fffffff)
        if u is not None:
            value = add(value, u)
        lfsr[:] = lfsr[1:] + [value]
    def next_f():
        nonlocal r1, r2
        x0 = ((lfsr[15] & 0x7fff8000) << 1) | (lfsr[14] & 65535)
        x1 = ((lfsr[11] & 65535) << 16) | (lfsr[9] >> 15)
        x2 = ((lfsr[7] & 65535) << 16) | (lfsr[5] >> 15)
        x3 = ((lfsr[2] & 65535) << 16) | (lfsr[0] >> 15)
        w = ((x0 ^ r1) + r2) & mask
        w1 = (r1 + x1) & mask
        w2 = r2 ^ x2
        r1 = sub(nonlinear(((w1 << 16) | (w2 >> 16)) & mask, (2, 10, 18, 24)))
        r2 = sub(nonlinear(((w2 << 16) | (w1 >> 16)) & mask, (8, 14, 22, 30)))
        return w, x3
    for _ in range(32):
        w, _ = next_f()
        state_step(w >> 1)
    next_f()
    state_step()
    result = bytearray()
    for pos in range(0, len(data), 4):
        w, x3 = next_f()
        stream = struct.pack('>I', w ^ x3)
        state_step()
        result.extend(a ^ b for a, b in zip(data[pos:pos + 4], stream))
    return bytes(result)


def native_profile(library):
    require(sha(library) == NATIVE_SHA, 'NATIVE_LIBRARY_PROFILE_UNKNOWN')
    elf = Elf32(library)
    key = b''.join(elf.at(a, 4) for a in (0x4808, 0x47fc, 0x47f0, 0x47f8))
    iv = b''.join(elf.at(a, 4) for a in (0x47f4, 0x4800, 0x4804, 0x47ec))
    return key, iv


def parse_container(module):
    require(5 <= len(module) <= MAX_INPUT and module[:3] == b'001', 'MODULE_FORMAT_UNKNOWN')
    pos = 3
    fields = []
    for _ in range(2):
        require(pos < len(module), 'MODULE_HEADER_TRUNCATED')
        count = module[pos]
        pos += 1
        require(count <= len(module) - pos, 'MODULE_HEADER_TRUNCATED')
        fields.append(module[pos:pos + count])
        pos += count
    require(re.fullmatch(rb'[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}', fields[1]) is not None, 'MODULE_ID_INVALID')
    require(pos + 9 < len(module), 'MODULE_BODY_TRUNCATED')
    props = module[pos:pos + 5]
    descriptor = struct.unpack_from('<I', module, pos + 5)[0]
    kind, expected = descriptor >> 28, descriptor & 0x0fffffff
    require(kind in (0, 3), 'MODULE_ENCODING_PROFILE_UNKNOWN')
    require(52 <= expected <= MAX_UNPACKED, 'MODULE_OUTPUT_SIZE_REJECTED')
    payload_size = len(module) - pos - 9
    if kind == 3:
        require(payload_size % 4 == 0, 'MODULE_STREAM_ALIGNMENT_REJECTED')
    else:
        require(payload_size >= 2, 'MODULE_STREAM_SIZE_REJECTED')
    prop = props[0]
    lc, prop = prop % 9, prop // 9
    lp, pb = prop % 5, prop // 5
    dictionary = struct.unpack_from('<I', props, 1)[0]
    require(pb <= 4 and lc + lp <= 4 and 4096 <= dictionary <= MAX_DICT, 'MODULE_LZMA_PROPERTIES_REJECTED')
    filters = [dict(id=lzma.FILTER_LZMA1, dict_size=dictionary, lc=lc, lp=lp, pb=pb)]
    return pos, fields, props, expected, filters


def inspect_elf(elf_data):
    elf = Elf32(elf_data)
    text = elf.sections.get('.text')
    require(text is not None, 'MODULE_CODE_PROFILE_UNKNOWN')
    profile = next((item for item in PROFILES
        if (text[3], text[5], sha(elf.file(text[4], text[5]))) == item['sections']['.text']), None)
    require(profile is not None, 'MODULE_CODE_PROFILE_UNKNOWN')
    for name, (va, size, expected_sha) in profile['sections'].items():
        require(name in elf.sections, 'MODULE_CODE_PROFILE_UNKNOWN')
        sec = elf.sections[name]
        require(sec[3] == va and sec[5] == size and sha(elf.file(sec[4], sec[5])) == expected_sha, 'MODULE_CODE_PROFILE_UNKNOWN')
    pools = [elf.pc_relative(a) for a in profile['references']]
    require(len(set(pools)) == 1, 'POOL_REFERENCES_DISAGREE')
    pool_va = pools[0]
    pool = elf.at(pool_va, 256, writable=True)
    skip, key_len, iv_len = pool[0xfe], pool[0xfb], pool[0xf9]
    require(key_len == profile['key_bytes'] and iv_len == profile['iv_bytes'], 'PARAMETER_LAYOUT_UNKNOWN')
    require(skip + key_len + iv_len + 7 <= 256, 'PARAMETER_RANGE_REJECTED')
    parameters = pool[skip:skip + key_len + iv_len]
    # 只放行受控完整ELF中的选定参数字节轮换；锁定导入、重定位、构造器和其它数据。
    masked = bytearray(elf_data)
    parameters_offset = elf.file_offset(pool_va + skip, key_len + iv_len)
    masked[parameters_offset:parameters_offset + key_len + iv_len] = bytes(key_len + iv_len)
    require(sha(masked) == profile['masked_sha'], 'MODULE_IMAGE_PROFILE_UNKNOWN')
    report = dict(profile=profile['name'], elf_sha256=sha(elf_data), text_sha256=profile['sections']['.text'][2],
        masked_image_sha256=profile['masked_sha'],
        pool_vaddr=hex(pool_va), pool_skip=skip, key_bytes=key_len, iv_bytes=iv_len,
        algorithm=profile['algorithm'], block_bytes=profile['block_bytes'],
        padding=profile['padding'], hex_encoding='uppercase', input_termination='NUL')
    if 'rounds_per_layer' in profile:
        report['rounds_per_layer'] = profile['rounds_per_layer']
    return parameters, report, elf, pool


def unpack_container(module, library, tables):
    # type0也绑定已识别loader身份，不能把任何外层数据都当作可信模块。
    loader_key, loader_iv = native_profile(library)
    body, fields, props, expected, filters = parse_container(module)
    kind = struct.unpack_from('<I', module, body + 5)[0] >> 28
    if kind == 0:
        decoded = bytearray(module[body + 9:])
        # 从最后一个相邻字节对回溯到首字节；不截去type3的外层填充。
        for position in range(len(decoded) - 2, -1, -1):
            decoded[position] ^= decoded[position + 1]
        compressed = bytes(decoded)
    else:
        decoded = zuc_process(loader_key, loader_iv, module[body + 9:], tables)
        padding = decoded[-1]
        # 原库只按末计数字节截断；此前填充字节不固定，不伪造PKCS7约束。
        require(1 <= padding <= 4 and padding < len(decoded), 'MODULE_PADDING_REJECTED')
        compressed = decoded[:-padding]
    stream = lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=filters)
    # 原native调用FINISH_ANY并将destLen限制为声明长度；无EOS流不可多解一个字节判错。
    elf_data = stream.decompress(compressed, max_length=expected)
    require(len(elf_data) == expected, 'MODULE_UNPACKED_LENGTH_MISMATCH')
    return elf_data, (body, fields, props, filters), kind, stream.eof


def decode(module, library, tables):
    elf_data, context, kind, eof = unpack_container(module, library, tables)
    body, fields, props, filters = context
    parameters, report, elf, pool = inspect_elf(elf_data)
    report.update(module_sha256=sha(module), native_library_sha256=NATIVE_SHA,
        algorithm_id=fields[1].decode().upper(), module_format='001', container_encoding_type=kind,
        container_encoding=('reverse adjacent XOR + LZMA1 raw' if kind == 0 else 'ZUC128 + LZMA1 raw'),
        module_bytes=len(module), elf_bytes=len(elf_data), lzma_end_marker=eof)
    return parameters, report, elf_data, context


def encode_like(module, context, elf_data, library, tables, encoding_type=None):
    body, _, props, filters = context
    kind = (struct.unpack_from('<I', module, body + 5)[0] >> 28) if encoding_type is None else encoding_type
    require(kind in (0, 3), 'MODULE_ENCODING_PROFILE_UNKNOWN')
    compressed = lzma.compress(elf_data, format=lzma.FORMAT_RAW, filters=filters)
    if kind == 0:
        # 回溯累积XOR的逆变换：相邻原字节XOR，最后字节保持不变。
        payload = bytearray(compressed)
        for position in range(len(payload) - 1):
            payload[position] ^= payload[position + 1]
        return module[:body] + props + struct.pack('<I', len(elf_data)) + bytes(payload)
    padding = 4 - len(compressed) % 4
    padded = compressed + bytes(padding - 1) + bytes([padding])
    key, iv = native_profile(library)
    return module[:body] + props + struct.pack('<I', 0x30000000 | len(elf_data)) + zuc_process(key, iv, padded, tables)


def private_write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(value)


def variants(directory, module, library, tables, original_parameters, report, elf_data, context):
    require(not directory.exists(), 'VARIANT_OUTPUT_EXISTS')
    directory.mkdir(parents=True)
    body, fields, _, _ = context
    new_id = b'11111111-1111-4111-8111-111111111111'
    changed_header = module[:3] + bytes([len(fields[0])]) + fields[0] + bytes([len(new_id)]) + new_id + module[body:]
    first, first_report, first_elf, _ = decode(changed_header, library, tables)
    require(first == original_parameters and first_elf == elf_data, 'HEADER_VARIANT_INVALID')
    private_write(directory / 'header-uuid-only.zsm', changed_header)
    target = bytearray(elf_data)
    elf = Elf32(elf_data)
    pool_va = int(report['pool_vaddr'], 16)
    pool_offset = elf.file_offset(pool_va, 256)
    skip = report['pool_skip']
    key_bytes, iv_bytes = report['key_bytes'], report['iv_bytes']
    synthetic = bytes(range(key_bytes)) + bytes(range(0xa0, 0xa0 + iv_bytes))
    target[pool_offset + skip:pool_offset + skip + key_bytes + iv_bytes] = synthetic
    changed_pool = encode_like(module, context, bytes(target), library, tables)
    second, second_report, second_elf, _ = decode(changed_pool, library, tables)
    require(second == synthetic and second_elf == bytes(target), 'POOL_VARIANT_INVALID')
    require(second_report['text_sha256'] == report['text_sha256'], 'POOL_VARIANT_CODE_CHANGED')
    private_write(directory / 'parameter-pool-only.zsm', changed_pool)
    private_write(directory / 'synthetic-expected-parameters.bin', synthetic)
    manifest = dict(scope='synthetic offline rotation variants; no ARM execution, no server requests',
        header_variant=dict(file='header-uuid-only.zsm', module_sha256=sha(changed_header),
            changed='header UUID only', same_parameters=True, same_unpacked_elf=True),
        pool_variant=dict(file='parameter-pool-only.zsm', module_sha256=sha(changed_pool),
            changed=f'{key_bytes + iv_bytes} selected pool bytes only before repack', same_text=True, same_original_id=True,
            expected_parameters='synthetic-expected-parameters.bin'),
        native_verified=False)
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return manifest


def find_zuc_source():
    for parent in Path(__file__).resolve().parents:
        path = parent / 'Products/DockerCpp/third_party/ciphers/algo/zuc.c'
        if path.is_file():
            return path
    raise ValueError('REFERENCE_ZUC_SOURCE_REQUIRED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', type=Path, required=True)
    parser.add_argument('--native-library', type=Path, required=True)
    parser.add_argument('--reference-zuc-source', type=Path)
    parser.add_argument('--private-parameters-output', type=Path)
    parser.add_argument('--make-private-variants', type=Path)
    args = parser.parse_args()
    try:
        require(args.module.stat().st_size <= MAX_INPUT and args.native_library.stat().st_size <= MAX_INPUT, 'INPUT_SIZE_REJECTED')
        module, library = args.module.read_bytes(), args.native_library.read_bytes()
        tables = zuc_tables(args.reference_zuc_source or find_zuc_source())
        parameters, report, elf_data, context = decode(module, library, tables)
        if args.private_parameters_output:
            private_write(args.private_parameters_output, parameters)
            report['private_parameters_written'] = True
        if args.make_private_variants:
            variants(args.make_private_variants, module, library, tables, parameters, report, elf_data, context)
            report['synthetic_variants_written'] = 2
            report['synthetic_variants_native_verified'] = False
        report['executes_arm'] = False
        report['login_attempted'] = False
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (ValueError, OSError, lzma.LZMAError, struct.error, UnicodeError) as error:
        # 固定错误码或异常类别，不打印不可信正文、参数或路径。
        code = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z0-9_]+', str(error)) else type(error).__name__
        print(json.dumps(dict(error=code, executes_arm=False, login_attempted=False)))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
