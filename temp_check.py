import zipfile, struct, os

AAB_PATH = "app/release/app-release.aab"
TARGET_LIB = "libimage_processing_util_jni.so"

def check_elf_alignment(data):
    """Check ELF PT_LOAD segment alignment"""
    if data[:4] != b'\x7fELF':
        return None, "Not ELF"
    bits = data[4]  # 1=32bit, 2=64bit
    endian = data[5]  # 1=little, 2=big
    fmt_char = '<' if endian == 1 else '>'
    
    if bits == 1:  # 32-bit
        e_phoff, = struct.unpack_from(fmt_char + 'I', data, 28)
        e_phentsize, = struct.unpack_from(fmt_char + 'H', data, 42)
        e_phnum, = struct.unpack_from(fmt_char + 'H', data, 44)
        load_aligns = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type, = struct.unpack_from(fmt_char + 'I', data, off)
            if p_type == 1:  # PT_LOAD
                p_align, = struct.unpack_from(fmt_char + 'I', data, off + 28)
                load_aligns.append(p_align)
    else:  # 64-bit
        e_phoff, = struct.unpack_from(fmt_char + 'Q', data, 32)
        e_phentsize, = struct.unpack_from(fmt_char + 'H', data, 54)
        e_phnum, = struct.unpack_from(fmt_char + 'H', data, 56)
        load_aligns = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type, = struct.unpack_from(fmt_char + 'I', data, off)
            if p_type == 1:  # PT_LOAD
                p_align, = struct.unpack_from(fmt_char + 'Q', data, off + 48)
                load_aligns.append(p_align)
    
    bits_str = "64-bit" if bits == 2 else "32-bit"
    return load_aligns, bits_str

print(f"Checking AAB: {AAB_PATH}")
print("=" * 70)

with zipfile.ZipFile(AAB_PATH) as z:
    all_so = [n for n in z.namelist() if n.endswith('.so')]
    target_entries = [n for n in all_so if TARGET_LIB in n]
    
    print(f"Total .so files in AAB: {len(all_so)}")
    print(f"Target lib entries ({TARGET_LIB}): {len(target_entries)}")
    print()
    
    # Check all .so files for alignment issues
    issues = []
    ok_libs = []
    for name in sorted(all_so):
        data = z.read(name)
        aligns, bits_str = check_elf_alignment(data)
        if aligns is None:
            continue
        min_align = min(aligns) if aligns else 0
        is_16k_ok = all(a >= 16384 for a in aligns)
        status = "OK (16KB aligned)" if is_16k_ok else f"FAIL (min alignment={min_align:#x}={min_align} bytes)"
        short_name = name.split('/')[-2] + '/' + name.split('/')[-1]
        if not is_16k_ok:
            issues.append((short_name, bits_str, aligns, status))
        else:
            ok_libs.append((short_name, bits_str, min_align))
    
    print(f"16KB-compliant .so files: {len(ok_libs)}")
    print(f"NON-compliant .so files: {len(issues)}")
    print()
    
    if issues:
        print("ISSUES FOUND:")
        for name, bits, aligns, status in issues:
            print(f"  [{bits}] {name}")
            print(f"    Alignments: {[hex(a) for a in aligns]}")
            print(f"    Status: {status}")
    else:
        print("All .so files pass 16KB alignment check!")
    
    print()
    print("ABIs present in AAB:")
    abis = set()
    for name in all_so:
        parts = name.split('/')
        for p in parts:
            if p in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
                abis.add(p)
    for abi in sorted(abis):
        count = sum(1 for n in all_so if f'/{abi}/' in n)
        print(f"  {abi}: {count} .so files")
