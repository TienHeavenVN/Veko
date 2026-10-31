#!/usr/bin/env bash
# Biên dịch thành phần gốc Veko Browser (Rust adblock-rust FFI + C++20/JNI qua GN + Ninja):
#   --mode=host    : Biên dịch libveko_chromium_native.so + veko_chromium_native_tests cho Linux x86_64 (JVM/C++ tests)
#   --mode=android : Biên dịch libveko_chromium_native.so cho 3 ABI Android (arm64-v8a, armeabi-v7a, x86_64)
#   --mode=all     : Chạy cả hai chế độ trên.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_CRATE_DIR="${REPO_ROOT}/privacy/rust/veko-adblock"

MODE="all"
for arg in "$@"; do
  case "${arg}" in
    --mode=host) MODE="host" ;;
    --mode=android) MODE="android" ;;
    --mode=all) MODE="all" ;;
    *)
      echo "[ERROR] Unknown argument: ${arg}" >&2
      exit 1
      ;;
  esac
done

resolve_gn_bin() {
  if command -v gn >/dev/null 2>&1; then
    command -v gn
    return 0
  fi
  echo "[ERROR] 'gn' binary not found in PATH. Install gn (e.g. 'pip install generate-ninja' or depot_tools)." >&2
  exit 1
}

resolve_ninja_bin() {
  if command -v ninja >/dev/null 2>&1; then
    command -v ninja
    return 0
  fi
  echo "[ERROR] 'ninja' binary not found in PATH. Install ninja-build." >&2
  exit 1
}

resolve_ndk_root() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    echo "${ANDROID_NDK_HOME}"
    return 0
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
    echo "${ANDROID_NDK_ROOT}"
    return 0
  fi
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "${sdk_root}" && -d "${sdk_root}/ndk" ]]; then
    local latest_ndk
    latest_ndk="$(ls -1 "${sdk_root}/ndk" | sort -V | tail -n 1)"
    if [[ -n "${latest_ndk}" && -d "${sdk_root}/ndk/${latest_ndk}" ]]; then
      echo "${sdk_root}/ndk/${latest_ndk}"
      return 0
    fi
  fi
  echo "[ERROR] Android NDK directory not found. Set ANDROID_NDK_HOME or install NDK under ANDROID_HOME/ndk." >&2
  exit 1
}

GN_BIN="$(resolve_gn_bin)"
NINJA_BIN="$(resolve_ninja_bin)"

build_host() {
  echo "==> [1/2] Compiling Rust adblock-rust engine for host (release + unit tests)..."
  (
    cd "${RUST_CRATE_DIR}"
    cargo build --release --locked 2>/dev/null || cargo build --release
  )

  local java_home="${JAVA_HOME:-}"
  if [[ -z "${java_home}" ]] && command -v javac >/dev/null 2>&1; then
    java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
  if [[ -z "${java_home}" || ! -f "${java_home}/include/jni.h" ]]; then
    echo "[ERROR] JAVA_HOME with include/jni.h is required for host JNI compilation." >&2
    exit 1
  fi

  local host_cxx="clang++"
  if ! command -v "${host_cxx}" >/dev/null 2>&1; then
    host_cxx="g++"
  fi

  local out_dir="${REPO_ROOT}/out/host_x64"
  local rust_lib_dir="${RUST_CRATE_DIR}/target/release"

  echo "==> [2/2] Generating & building host C++20/JNI + native test suite with GN + Ninja..."
  "${GN_BIN}" gen "${out_dir}" --root="${REPO_ROOT}" --args="
    cxx_compiler=\"${host_cxx}\"
    ar_tool=\"ar\"
    rust_lib_dir=\"${rust_lib_dir}\"
    jni_include_dir=\"${java_home}/include\"
    jni_md_include_dir=\"${java_home}/include/linux\"
    is_android_ndk=false
  "
  "${NINJA_BIN}" -C "${out_dir}" veko_chromium_native veko_chromium_native_tests
  echo "==> Host native outputs ready in ${out_dir}:"
  ls -lh "${out_dir}/libveko_chromium_native.so" "${out_dir}/veko_chromium_native_tests"
}

build_android_abi() {
  local abi="$1"
  local rust_target="$2"
  local clang_triple="$3"
  local ndk_root="$4"

  local prebuilt_bin="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  local ndk_clangxx="${prebuilt_bin}/clang++"
  local ndk_clang="${prebuilt_bin}/clang"
  local ndk_ar="${prebuilt_bin}/llvm-ar"
  local ndk_sysroot="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  local target_clang="${prebuilt_bin}/${clang_triple}-clang"

  echo "==> [Android ${abi}] Compiling Rust adblock-rust staticlib (${rust_target})..."
  local upper_target
  upper_target="$(echo "${rust_target}" | tr '[:lower:]-' '[:upper:]_')"
  (
    cd "${RUST_CRATE_DIR}"
    export "CARGO_TARGET_${upper_target}_LINKER=${target_clang}"
    export "CC_${rust_target//-/_}=${target_clang}"
    export "AR_${rust_target//-/_}=${ndk_ar}"
    cargo build --release --target "${rust_target}"
  )

  local out_dir="${REPO_ROOT}/out/android_${abi}"
  local rust_lib_dir="${RUST_CRATE_DIR}/target/${rust_target}/release"
  local jni_out_dir="${REPO_ROOT}/chromium-native/build/generated/gnJniLibs/${abi}"
  mkdir -p "${jni_out_dir}"

  echo "==> [Android ${abi}] Generating & building C++20 JNI shared library with GN + Ninja..."
  "${GN_BIN}" gen "${out_dir}" --root="${REPO_ROOT}" --args="
    cxx_compiler=\"${ndk_clangxx}\"
    ar_tool=\"${ndk_ar}\"
    target_triple_flag=\"--target=${clang_triple}\"
    rust_lib_dir=\"${rust_lib_dir}\"
    jni_include_dir=\"${ndk_sysroot}/usr/include\"
    is_android_ndk=true
  "
  "${NINJA_BIN}" -C "${out_dir}" veko_chromium_native
  cp -f "${out_dir}/libveko_chromium_native.so" "${jni_out_dir}/libveko_chromium_native.so"
  echo "==> [Android ${abi}] Packaged $(du -h "${jni_out_dir}/libveko_chromium_native.so" | cut -f1) -> ${jni_out_dir}/libveko_chromium_native.so"
}

if [[ "${MODE}" == "host" || "${MODE}" == "all" ]]; then
  build_host
fi

if [[ "${MODE}" == "android" || "${MODE}" == "all" ]]; then
  NDK_ROOT="$(resolve_ndk_root)"
  echo "==> Using Android NDK: ${NDK_ROOT}"
  build_android_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android26" "${NDK_ROOT}"
  build_android_abi "armeabi-v7a" "armv7-linux-androideabi" "armv7a-linux-androideabi26" "${NDK_ROOT}"
  build_android_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android26" "${NDK_ROOT}"
fi
