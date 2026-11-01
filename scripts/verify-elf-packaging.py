#!/usr/bin/env python3
"""
Bộ kiểm định nhị phân ELF64/ELF32 và gói APK thuần Python (không phụ thuộc binutils ngoài) cho Veko Browser:
- Xác minh kiến trúc máy (EM_AARCH64 / EM_ARM / EM_X86_64) khớp đúng thư mục ABI.
- Xác minh căn chỉnh trang bộ nhớ PT_LOAD p_align >= 16384 (16 KB page-size của Android 15/16) cho arm64-v8a và x86_64.
- Xác minh bảng thư viện động DT_NEEDED chỉ liên kết tới các thư viện hệ thống Android NDK hợp lệ (hoặc danh sách cho phép).
- Xác minh ký hiệu xuất động bắt buộc (ví dụ JNI_OnLoad trong .dynsym).
- Hỗ trợ kiểm tra trực tiếp tệp .so hoặc kiểm tra toàn bộ các tệp lib/<abi>/*.so đóng gói bên trong tệp .apk!
"""

import argparse
import os
import struct
import sys
import tempfile
import zipfile

ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
PT_DYNAMIC = 2
SHT_DYNSYM = 11
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_SONAME = 14

ABI_TO_EM = {
    "arm64-v8a": (62 if False else 183, 2),   # EM_AARCH64 = 183, ELFCLASS64 = 2
    "armeabi-v7a": (40, 1),                    # EM_ARM = 40, ELFCLASS32 = 1
    "x86_64": (62, 2),                         # EM_X86_64 = 62, ELFCLASS64 = 2
}

ALLOWED_ANDROID_SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "libEGL.so",
    "libGLESv2.so",
    "libGLESv3.so",
    "libjnigraphics.so",
    "liblog.so",
    "libm.so",
    "libmediandk.so",
    "libnativewindow.so",
    "libOpenSLES.so",
    "libstdc++.so",
    "libsync.so",
    "libvulkan.so",
    "libz.so",
    # Cho phép khi kiểm tra host Linux x86_64
    "libc.so.6",
    "libdl.so.2",
    "libgcc_s.so.1",
    "libm.so.6",
    "libpthread.so.0",
    "libstdc++.so.6",
    "ld-linux-x86-64.so.2",
}


def read_cstring(blob: bytes, offset: int) -> str:
    if offset < 0 or offset >= len(blob):
        return ""
    end = blob.find(b"\x00", offset)
    if end == -1:
        end = len(blob)
    return blob[offset:end].decode("utf-8", errors="replace")


def parse_and_verify_elf(
    path: str,
    expected_abi: str | None,
    require_page_size: int,
    required_symbols: list[str],
    allow_extra_needed: set[str],
) -> dict:
    with open(path, "rb") as f:
        data = f.read()

    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise ValueError(f"{path}: Not a valid ELF binary")

    ei_class = data[4]  # 1 = 32-bit, 2 = 64-bit
    ei_data = data[5]   # 1 = little-endian
    if ei_data != 1:
        raise ValueError(f"{path}: Expected little-endian ELF, got ei_data={ei_data}")

    if ei_class == 2:
        (
            _e_type,
            e_machine,
            _e_version,
            _e_entry,
            e_phoff,
            e_shoff,
            _e_flags,
            _e_ehsize,
            e_phentsize,
            e_phnum,
            e_shentsize,
            e_shnum,
            _e_shstrndx,
        ) = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    elif ei_class == 1:
        (
            _e_type,
            e_machine,
            _e_version,
            _e_entry,
            e_phoff,
            e_shoff,
            _e_flags,
            _e_ehsize,
            e_phentsize,
            e_phnum,
            e_shentsize,
            e_shnum,
            _e_shstrndx,
        ) = struct.unpack_from("<HHIIIIIHHHHHH", data, 16)
    else:
        raise ValueError(f"{path}: Unsupported ELF class {ei_class}")

    if expected_abi:
        exp_em, exp_cls = ABI_TO_EM[expected_abi]
        if e_machine != exp_em or ei_class != exp_cls:
            raise ValueError(
                f"{path}: ABI mismatch for {expected_abi}: got e_machine={e_machine}, class={ei_class}"
            )

    load_segments = []
    dynamic_offset = None
    dynamic_size = 0

    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if ei_class == 2:
            p_type, p_flags, p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = (
                struct.unpack_from("<IIQQQQQQ", data, off)
            )
        else:
            p_type, p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_flags, p_align = (
                struct.unpack_from("<IIIIIIII", data, off)
            )
        if p_type == PT_LOAD:
            load_segments.append((p_vaddr, p_offset, p_filesz, p_memsz, p_flags, p_align))
        elif p_type == PT_DYNAMIC:
            dynamic_offset = p_offset
            dynamic_size = p_filesz

    if not load_segments:
        raise ValueError(f"{path}: ELF contains zero PT_LOAD segments")

    min_load_align = min(seg[5] for seg in load_segments)
    # Yêu cầu căn chỉnh 16 KB áp dụng cho Android 64-bit (arm64-v8a, x86_64)
    if require_page_size > 0 and ei_class == 2:
        if min_load_align < require_page_size:
            raise ValueError(
                f"{path}: PT_LOAD min p_align={min_load_align} < required {require_page_size} (16 KB page compatibility failed)"
            )

    def vaddr_to_offset(vaddr: int) -> int | None:
        for seg_vaddr, seg_off, seg_filesz, _seg_memsz, _seg_flags, _seg_align in load_segments:
            if seg_vaddr <= vaddr < seg_vaddr + seg_filesz:
                return seg_off + (vaddr - seg_vaddr)
        return None

    needed_libs = []
    soname = ""
    if dynamic_offset is not None and dynamic_size > 0:
        entry_size = 16 if ei_class == 2 else 8
        fmt = "<qQ" if ei_class == 2 else "<iI"
        dyn_entries = []
        strtab_vaddr = None
        for pos in range(dynamic_offset, dynamic_offset + dynamic_size, entry_size):
            if pos + entry_size > len(data):
                break
            d_tag, d_val = struct.unpack_from(fmt, data, pos)
            if d_tag == DT_NULL:
                break
            dyn_entries.append((d_tag, d_val))
            if d_tag == DT_STRTAB:
                strtab_vaddr = d_val

        if strtab_vaddr is not None:
            strtab_off = vaddr_to_offset(strtab_vaddr)
            if strtab_off is not None:
                for d_tag, d_val in dyn_entries:
                    if d_tag == DT_NEEDED:
                        needed_libs.append(read_cstring(data, strtab_off + d_val))
                    elif d_tag == DT_SONAME:
                        soname = read_cstring(data, strtab_off + d_val)

    allowed = ALLOWED_ANDROID_SYSTEM_LIBS | allow_extra_needed
    unexpected = [lib for lib in needed_libs if lib not in allowed]
    if unexpected:
        raise ValueError(
            f"{path}: Unexpected DT_NEEDED dependencies {unexpected} (allowed: {sorted(allowed)})"
        )

    exported_symbols = set()
    if e_shoff > 0 and e_shnum > 0:
        sections = []
        sh_fmt = "<IIQQQQIIQQ" if ei_class == 2 else "<IIIIIIIIII"
        for i in range(e_shnum):
            soff = e_shoff + i * e_shentsize
            if soff + e_shentsize > len(data):
                break
            sections.append(struct.unpack_from(sh_fmt, data, soff))

        for sec in sections:
            sh_type = sec[1]
            sh_offset = sec[4]
            sh_size = sec[5]
            sh_link = sec[6]
            sh_entsize = sec[9]
            if sh_type == SHT_DYNSYM and sh_entsize > 0 and 0 <= sh_link < len(sections):
                str_sec = sections[sh_link]
                str_off = str_sec[4]
                for pos in range(sh_offset, sh_offset + sh_size, sh_entsize):
                    if ei_class == 2:
                        st_name, st_info, _st_other, st_shndx, _st_value, _st_size = (
                            struct.unpack_from("<IBBHQQ", data, pos)
                        )
                    else:
                        st_name, _st_value, _st_size, st_info, _st_other, st_shndx = (
                            struct.unpack_from("<IIIBBH", data, pos)
                        )
                    st_bind = st_info >> 4
                    if st_shndx != 0 and st_bind in (1, 2):  # STB_GLOBAL or STB_WEAK defined
                        sym_name = read_cstring(data, str_off + st_name)
                        if sym_name:
                            exported_symbols.add(sym_name)

    for req_sym in required_symbols:
        if req_sym not in exported_symbols:
            raise ValueError(
                f"{path}: Required exported dynamic symbol '{req_sym}' not found in .dynsym"
            )

    return {
        "path": path,
        "soname": soname,
        "min_load_align": min_load_align,
        "needed": needed_libs,
        "exported_count": len(exported_symbols),
    }


def verify_apk(apk_path: str, expected_abi: str, require_page_size: int) -> dict:
    with zipfile.ZipFile(apk_path, "r") as zf:
        all_names = zf.namelist()
        lib_abis = sorted({n.split("/")[1] for n in all_names if n.startswith("lib/") and len(n.split("/")) >= 3})
        if lib_abis != [expected_abi]:
            raise ValueError(
                f"{apk_path}: Expected single-ABI jniLibs ['{expected_abi}'], found {lib_abis}"
            )

        prefix = f"lib/{expected_abi}/"
        so_entries = [n for n in all_names if n.startswith(prefix) and n.endswith(".so")]
        veko_so = f"{prefix}libveko_chromium_native.so"
        if veko_so not in so_entries:
            raise ValueError(f"{apk_path}: Missing mandatory '{veko_so}' inside APK! Found: {so_entries}")

        report = {
            "apk": os.path.basename(apk_path),
            "abi": expected_abi,
            "packaged_so_count": len(so_entries),
            "verified_libraries": [],
        }

        with tempfile.TemporaryDirectory() as tmp:
            extracted_veko = zf.extract(veko_so, tmp)
            info = parse_and_verify_elf(
                path=extracted_veko,
                expected_abi=expected_abi,
                require_page_size=require_page_size,
                required_symbols=["JNI_OnLoad"],
                allow_extra_needed=set(),
            )
            report["verified_libraries"].append({"entry": veko_so, **info})
            print(
                f"[APK-ELF-OK] {os.path.basename(apk_path)} -> {veko_so}: "
                f"p_align={info['min_load_align']}, DT_NEEDED={info['needed']}"
            )

            embedder_so = f"{prefix}libveko_content_embedder.so"
            if embedder_so in so_entries:
                extracted_embedder = zf.extract(embedder_so, tmp)
                emb_info = parse_and_verify_elf(
                    path=extracted_embedder,
                    expected_abi=expected_abi,
                    require_page_size=require_page_size,
                    required_symbols=["JNI_OnLoad"],
                    allow_extra_needed=set(),
                )
                report["verified_libraries"].append({"entry": embedder_so, **emb_info})
                for pak_asset in ("assets/veko_content.pak", "assets/icudtl.dat"):
                    if pak_asset not in all_names:
                        raise ValueError(f"{apk_path}: Missing mandatory Chromium asset '{pak_asset}'")
                    zinfo = zf.getinfo(pak_asset)
                    if zinfo.compress_type != zipfile.ZIP_STORED:
                        raise ValueError(
                            f"{apk_path}: Chromium asset '{pak_asset}' must be uncompressed (ZIP_STORED) for mmap"
                        )
        return report


def main() -> int:
    import json

    parser = argparse.ArgumentParser(description="Verify Veko ELF shared libraries and APKs.")
    parser.add_argument(
        "--abi",
        "--expected-abi",
        dest="abi",
        choices=["arm64-v8a", "armeabi-v7a", "x86_64"],
        default=None,
    )
    parser.add_argument("--require-page-size", type=int, default=16384)
    parser.add_argument("--require-symbol", action="append", default=[])
    parser.add_argument("--require-jni-onload", action="store_true")
    parser.add_argument("--allow-needed", action="append", default=[])
    parser.add_argument("--apk", action="store_true", help="Treat input paths as Android .apk files")
    parser.add_argument("--check-so", action="append", default=[])
    parser.add_argument("--check-apk", action="append", default=[])
    parser.add_argument("--output-json", default=None)
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()

    req_symbols = list(args.require_symbol)
    if args.require_jni_onload and "JNI_OnLoad" not in req_symbols:
        req_symbols.append("JNI_OnLoad")

    all_targets = (
        [(p, False) for p in args.check_so]
        + [(p, True) for p in args.check_apk]
        + [(p, args.apk or p.endswith(".apk")) for p in args.paths]
    )
    if not all_targets:
        parser.error("At least one ELF .so or .apk path is required.")

    results = []
    for p, is_apk in all_targets:
        if is_apk:
            if not args.abi:
                raise SystemExit("--abi / --expected-abi is required when verifying an .apk")
            results.append(verify_apk(p, args.abi, args.require_page_size))
        else:
            info = parse_and_verify_elf(
                path=p,
                expected_abi=args.abi,
                require_page_size=args.require_page_size,
                required_symbols=req_symbols,
                allow_extra_needed=set(args.allow_needed),
            )
            results.append(info)
            print(
                f"[ELF-OK] {p}: soname={info['soname'] or 'n/a'}, "
                f"min_p_align={info['min_load_align']}, DT_NEEDED={info['needed']}"
            )

    if args.output_json:
        os.makedirs(os.path.dirname(os.path.abspath(args.output_json)), exist_ok=True)
        with open(args.output_json, "w", encoding="utf-8") as fh:
            json.dump(results, fh, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
