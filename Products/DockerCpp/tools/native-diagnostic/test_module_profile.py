"""使用调用者提供的私有模块验证受控profile，不包含/发布模块和密钥fixture。"""
import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
import module_profile_diagnostic as m


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', required=True, type=Path)
    parser.add_argument('--native-library', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--type0-module', type=Path, help='可选实际type0样本，仅外层解包自检，不绕过内部profile校验')
    parser.add_argument('--type0-unpacked', type=Path, help='上个样本对应的私有已核对ELF')
    args = parser.parse_args()
    module, library = args.module.read_bytes(), args.native_library.read_bytes()
    tables = m.zuc_tables(m.find_zuc_source())
    checks = []

    def check(label, value):
        assert value, label
        checks.append(label)

    def reject(label, function, code):
        try:
            function()
        except ValueError as error:
            check(label, str(error) == code)
        else:
            raise AssertionError(label + ' accepted')

    parameters, report, elf, context = m.decode(module, library, tables)
    check('parameters_bounded', len(parameters) == report['key_bytes'] + report['iv_bytes'] and
          (report['key_bytes'], report['iv_bytes']) in {(48, 8), (16, 16)})
    check('ZUC_known_zero_vector', m.zuc_process(bytes(16), bytes(16), bytes(8), tables).hex() == '27bede74018082da')
    # 同一已识别内部ELF重装为type0，再独立验证容器分支和内部profile约束。
    encoded_zero = m.encode_like(module, context, elf, library, tables, encoding_type=0)
    p0, r0, e0, zero_context = m.decode(encoded_zero, library, tables)
    check('type0_known_profile_roundtrip', p0 == parameters and e0 == elf and r0['container_encoding_type'] == 0)
    check('type0_encoding_inverse_preserves_compressed_stream', m.unpack_container(encoded_zero, library, tables)[0] == elf)
    encoded_three = m.encode_like(module, context, elf, library, tables, encoding_type=3)
    p3, r3, e3, _ = m.decode(encoded_three, library, tables)
    check('type3_known_profile_roundtrip', p3 == parameters and e3 == elf and r3['container_encoding_type'] == 3)
    check('container_encoding_does_not_select_cipher_profile', r3['profile'] == r0['profile'] == report['profile'])
    zero_body = zero_context[0]
    reject('type0_too_short', lambda: m.parse_container(encoded_zero[:zero_body + 10]), 'MODULE_STREAM_SIZE_REJECTED')
    if args.type0_module or args.type0_unpacked:
        assert args.type0_module and args.type0_unpacked, 'Both type0 inputs are required'
        actual_zero = args.type0_module.read_bytes()
        unpacked_zero, _, kind, _ = m.unpack_container(actual_zero, library, tables)
        check('type0_actual_outer_decode', kind == 0 and unpacked_zero == args.type0_unpacked.read_bytes())
        actual_parameters, actual_report, actual_elf, _ = m.decode(actual_zero, library, tables)
        check('type0_actual_inner_profile_checked', actual_elf == unpacked_zero and len(actual_parameters) ==
              actual_report['key_bytes'] + actual_report['iv_bytes'])
    with tempfile.TemporaryDirectory(prefix='module-profile-test-') as temporary:
        root = Path(temporary)
        variants = root / 'variants'
        m.variants(variants, module, library, tables, parameters, report, elf, context)
        p1, r1, e1, _ = m.decode((variants / 'header-uuid-only.zsm').read_bytes(), library, tables)
        check('header_rotation_same_code_parameters', r1['algorithm_id'] != report['algorithm_id'] and e1 == elf and p1 == parameters)
        p2, r2, e2, _ = m.decode((variants / 'parameter-pool-only.zsm').read_bytes(), library, tables)
        check('parameter_rotation_same_code', r2['algorithm_id'] == report['algorithm_id'] and p2 == (variants / 'synthetic-expected-parameters.bin').read_bytes() and r2['text_sha256'] == report['text_sha256'])
        reject('truncated_header', lambda: m.decode(module[:5], library, tables), 'MODULE_HEADER_TRUNCATED')
        reject('oversize_input', lambda: m.decode(module + bytes(m.MAX_INPUT), library, tables), 'MODULE_FORMAT_UNKNOWN')
        body = context[0]
        container_type = report['container_encoding_type']
        for label, offset, value, code in (
            ('unknown_encoding', body + 5, 0x40009700, 'MODULE_ENCODING_PROFILE_UNKNOWN'),
            ('oversize_target', body + 5, (container_type << 28) | (m.MAX_UNPACKED + 1), 'MODULE_OUTPUT_SIZE_REJECTED'),
            ('oversize_dictionary', body + 1, m.MAX_DICT + 1, 'MODULE_LZMA_PROPERTIES_REJECTED'),
        ):
            bad = bytearray(module)
            struct.pack_into('<I', bad, offset, value)
            reject(label, lambda: m.decode(bad, library, tables), code)
        if container_type == 3:
            reject('truncated_alignment', lambda: m.decode(module[:-1], library, tables), 'MODULE_STREAM_ALIGNMENT_REJECTED')
            bad = bytearray(module)
            bad[-1] ^= 4
            reject('invalid_padding', lambda: m.decode(bad, library, tables), 'MODULE_PADDING_REJECTED')
        else:
            reject('type0_truncated_payload', lambda: m.parse_container(module[:body + 10]), 'MODULE_STREAM_SIZE_REJECTED')
        parsed = m.Elf32(elf)
        pool = int(report['pool_vaddr'], 16)
        text_address = parsed.sections['.text'][3]
        for label, address, value, code in (
            ('code_changed', text_address, None, 'MODULE_CODE_PROFILE_UNKNOWN'),
            ('unknown_key_layout', pool + 0xfb, report['key_bytes'] + 1, 'PARAMETER_LAYOUT_UNKNOWN'),
            ('pool_range_invalid', pool + 0xfe, 250, 'PARAMETER_RANGE_REJECTED'),
            ('nonparameter_pool_changed', pool, None, 'MODULE_IMAGE_PROFILE_UNKNOWN'),
        ):
            changed = bytearray(elf)
            offset = parsed.file_offset(address, 1)
            changed[offset] = changed[offset] ^ 1 if value is None else value
            encoded = m.encode_like(module, context, bytes(changed), library, tables)
            reject(label, lambda: m.decode(encoded, library, tables), code)
        for section in ('.dynstr', '.rel.plt'):
            changed = bytearray(elf)
            changed[parsed.sections[section][4]] ^= 1
            encoded = m.encode_like(module, context, bytes(changed), library, tables)
            reject('binding_changed_' + section, lambda: m.decode(encoded, library, tables), 'MODULE_IMAGE_PROFILE_UNKNOWN')
        changed_library = bytearray(library)
        changed_library[-1] ^= 1
        reject('unknown_library', lambda: m.decode(module, changed_library, tables), 'NATIVE_LIBRARY_PROFILE_UNKNOWN')
        changed = bytearray(elf)
        section_offset = struct.unpack_from('<I', changed, 32)[0]
        section_count = struct.unpack_from('<H', changed, 48)[0]
        for index in range(section_count):
            entry = section_offset + index * 40
            if struct.unpack_from('<I', changed, entry + 12)[0] == text_address:
                struct.pack_into('<I', changed, entry + 16, len(changed) + 1)
                break
        encoded = m.encode_like(module, context, bytes(changed), library, tables)
        reject('ELF_section_range', lambda: m.decode(encoded, library, tables), 'ELF_RANGE_REJECTED')
        private = root / 'private-parameters.bin'
        private.write_bytes(b'do-not-overwrite')
        before = hashlib.sha256(private.read_bytes()).hexdigest()
        process = subprocess.run([sys.executable, str(Path(m.__file__)), '--module', str(args.module),
            '--native-library', str(args.native_library), '--private-parameters-output', str(private)], capture_output=True, text=True)
        check('private_output_no_overwrite', process.returncode == 1 and 'FileExistsError' in process.stdout and hashlib.sha256(private.read_bytes()).hexdigest() == before)
    result = dict(checks_passed=len(checks), checks=checks, arm_executed=False, network_accessed=False, private_values_printed=False)
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
    print(json.dumps(result))


if __name__ == '__main__':
    main()
