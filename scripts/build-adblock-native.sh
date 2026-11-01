#!/usr/bin/env bash
# Biên dịch cầu nối gốc độc lập với browser engine cho module :privacy (Rust adblock-rust FFI + C++20 JNI):
#   --mode=host    : Biên dịch libveko_adblock_jni.so + veko_adblock_native_tests cho Linux x86_64 (JVM/C++ tests)
#   --mode=android : Biên dịch libveko_adblock_jni.so cho ABI Android (arm64-v8a, armeabi-v7a, x86_64)
#   --mode=all     : Chạy cả hai chế độ trên
#   --abi=<abi>    : Giới hạn ABI Android cần build (arm64-v8a | armeabi-v7a | x86_64 | all)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUST_CRATE_DIR="${REPO_ROOT}/privacy/rust/veko-adblock"
CPP_MAIN_DIR="${REPO_ROOT}/privacy/src/main/cpp"
CPP_TEST_DIR="${REPO_ROOT}/privacy/src/test/cpp"
ELF_VERIFIER="${SCRIPT_DIR}/verify-elf-packaging.py"

MODE="all"
ABI_FILTER="all"

for arg in "$@"; do
  case "${arg}" in
    --mode=host) MODE="host" ;;
    --mode=android) MODE="android" ;;
    --mode=all) MODE="all" ;;
    --abi=arm64-v8a|--abi=armeabi-v7a|--abi=x86_64|--abi=all)
      ABI_FILTER="${arg#--abi=}"
      ;;
    *)
      echo "[ERROR] Unknown argument: ${arg}" >&2
      exit 1
      ;;
  esac
done

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

if [[ ! -f "${RUST_CRATE_DIR}/Cargo.lock" ]]; then
  echo "[ERROR] Missing ${RUST_CRATE_DIR}/Cargo.lock — deterministic locked Rust builds require Cargo.lock." >&2
  exit 1
fi

CARGO_LOCKED_ARGS=(--release --locked)
if [[ "${VEKO_OFFLINE_BUILD:-false}" == "true" ]] || [[ -d "${RUST_CRATE_DIR}/vendor" && -f "${RUST_CRATE_DIR}/.cargo/config.toml" ]]; then
  export CARGO_NET_OFFLINE=true
  CARGO_LOCKED_ARGS+=(--offline)
  echo "==> Rust Cargo mode: OFFLINE & LOCKED (CARGO_NET_OFFLINE=true)"
else
  echo "==> Rust Cargo mode: LOCKED (verified against committed Cargo.lock)"
fi

REPRO_RUSTFLAGS="--remap-path-prefix=${REPO_ROOT}=. --remap-path-prefix=${HOME:-/root}/.cargo=/cargo"

COMMON_CXXFLAGS=(
  -std=c++20
  -O2
  -fPIC
  -ffunction-sections
  -fdata-sections
  -Wall
  -Wextra
  "-ffile-prefix-map=${REPO_ROOT}=."
  "-I${CPP_MAIN_DIR}"
  "-I${RUST_CRATE_DIR}/include"
)

build_host() {
  echo "==> [1/2] Compiling Rust adblock-rust engine for host (release + locked)..."
  (
    cd "${RUST_CRATE_DIR}"
    RUSTFLAGS="${RUSTFLAGS:+${RUSTFLAGS} }${REPRO_RUSTFLAGS}" cargo build "${CARGO_LOCKED_ARGS[@]}"
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
  local rust_staticlib="${RUST_CRATE_DIR}/target/release/libveko_adblock_ffi.a"
  mkdir -p "${out_dir}"

  echo "==> [2/2] Compiling host C++20 JNI bridge & native test suite with ${host_cxx}..."
  "${host_cxx}" "${COMMON_CXXFLAGS[@]}" \
    -fvisibility=default \
    "-I${java_home}/include" \
    "-I${java_home}/include/linux" \
    -shared \
    "${CPP_MAIN_DIR}/veko_shield_pipeline.cc" \
    "${CPP_MAIN_DIR}/veko_adblock_jni.cc" \
    "${rust_staticlib}" \
    -Wl,--gc-sections \
    -lpthread -ldl -lm \
    -o "${out_dir}/libveko_adblock_jni.so"

  "${host_cxx}" "${COMMON_CXXFLAGS[@]}" \
    "${CPP_MAIN_DIR}/veko_shield_pipeline.cc" \
    "${CPP_TEST_DIR}/veko_adblock_native_tests.cc" \
    "${rust_staticlib}" \
    -Wl,--gc-sections \
    -lpthread -ldl -lm \
    -o "${out_dir}/veko_adblock_native_tests"

  echo "==> Host native outputs ready in ${out_dir}:"
  ls -lh "${out_dir}/libveko_adblock_jni.so" "${out_dir}/veko_adblock_native_tests"
}

build_android_abi() {
  local abi="$1"
  local rust_target="$2"
  local clang_triple="$3"
  local ndk_root="$4"

  local prebuilt_bin="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  local ndk_ar="${prebuilt_bin}/llvm-ar"
  local ndk_sysroot="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  local target_clang="${prebuilt_bin}/${clang_triple}-clang"
  local target_clangxx="${prebuilt_bin}/${clang_triple}-clang++"

  echo "==> [Android ${abi}] Compiling Rust adblock-rust staticlib (${rust_target})..."
  local upper_target
  upper_target="$(echo "${rust_target}" | tr '[:lower:]-' '[:upper:]_')"
  (
    cd "${RUST_CRATE_DIR}"
    export "CARGO_TARGET_${upper_target}_LINKER=${target_clang}"
    export "CC_${rust_target//-/_}=${target_clang}"
    export "AR_${rust_target//-/_}=${ndk_ar}"
    RUSTFLAGS="${RUSTFLAGS:+${RUSTFLAGS} }${REPRO_RUSTFLAGS}" cargo build "${CARGO_LOCKED_ARGS[@]}" --target "${rust_target}"
  )

  local rust_staticlib="${RUST_CRATE_DIR}/target/${rust_target}/release/libveko_adblock_ffi.a"
  local jni_out_dir="${REPO_ROOT}/privacy/build/generated/adblockJniLibs/${abi}"
  mkdir -p "${jni_out_dir}"

  echo "==> [Android ${abi}] Linking 16 KB-aligned libveko_adblock_jni.so with Android NDK Clang++..."
  "${target_clangxx}" "${COMMON_CXXFLAGS[@]}" \
    -fvisibility=default \
    "-I${ndk_sysroot}/usr/include" \
    -shared \
    "${CPP_MAIN_DIR}/veko_shield_pipeline.cc" \
    "${CPP_MAIN_DIR}/veko_adblock_jni.cc" \
    "${rust_staticlib}" \
    -static-libstdc++ \
    -Wl,--gc-sections \
    -Wl,--exclude-libs,ALL \
    -Wl,-z,max-page-size=16384 \
    -landroid -llog -ldl -lm \
    -o "${jni_out_dir}/libveko_adblock_jni.so"

  if [[ -f "${ELF_VERIFIER}" ]]; then
    python3 "${ELF_VERIFIER}" \
      --check-so "${jni_out_dir}/libveko_adblock_jni.so" \
      --expected-abi "${abi}" \
      --require-jni-onload
  fi

  echo "==> [Android ${abi}] Packaged $(du -h "${jni_out_dir}/libveko_adblock_jni.so" | cut -f1) -> ${jni_out_dir}/libveko_adblock_jni.so"
}

if [[ "${MODE}" == "host" || "${MODE}" == "all" ]]; then
  build_host
fi

if [[ "${MODE}" == "android" || "${MODE}" == "all" ]]; then
  NDK_ROOT="$(resolve_ndk_root)"
  echo "==> Using Android NDK: ${NDK_ROOT}"
  if [[ "${ABI_FILTER}" == "all" || "${ABI_FILTER}" == "arm64-v8a" ]]; then
    build_android_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android26" "${NDK_ROOT}"
  fi
  if [[ "${ABI_FILTER}" == "all" || "${ABI_FILTER}" == "armeabi-v7a" ]]; then
    build_android_abi "armeabi-v7a" "armv7-linux-androideabi" "armv7a-linux-androideabi26" "${NDK_ROOT}"
  fi
  if [[ "${ABI_FILTER}" == "all" || "${ABI_FILTER}" == "x86_64" ]]; then
    build_android_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android26" "${NDK_ROOT}"
  fi
fi
