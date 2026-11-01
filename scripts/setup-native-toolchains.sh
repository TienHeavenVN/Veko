#!/usr/bin/env bash
# Cài đặt chuỗi công cụ gốc (Clang/C++20 + Rust >= 1.86 + Android NDK targets) cho cầu nối Rust adblock-rust của Veko Browser.
# Kiến trúc GeckoView-only không còn yêu cầu GN hay Ninja.
set -euo pipefail

WITH_ANDROID="false"
for arg in "$@"; do
  case "${arg}" in
    --with-android) WITH_ANDROID="true" ;;
  esac
done

echo "==> [1/3] Checking host C++20 compiler (clang++ or g++)..."
if ! command -v clang++ >/dev/null 2>&1 && ! command -v g++ >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends clang
fi
if command -v clang++ >/dev/null 2>&1; then
  echo "Host C++ compiler: $(clang++ --version | head -n 1)"
else
  echo "Host C++ compiler: $(g++ --version | head -n 1)"
fi

echo "==> [2/3] Checking Rust toolchain (requires rustc >= 1.86 for brave/adblock-rust 0.13.4 + icu4x 2.2)..."
if ! command -v rustup >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
fi
if [[ -d "${HOME}/.cargo/bin" ]]; then
  export PATH="${HOME}/.cargo/bin:${PATH}"
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "${HOME}/.cargo/bin" >> "${GITHUB_PATH}"
  fi
fi

NEED_RUST_UPDATE="false"
if command -v rustc >/dev/null 2>&1; then
  RUST_MINOR="$(rustc --version | awk '{print $2}' | cut -d. -f2)"
  RUST_MAJOR="$(rustc --version | awk '{print $2}' | cut -d. -f1)"
  if [[ "${RUST_MAJOR}" -lt 1 ]] || { [[ "${RUST_MAJOR}" -eq 1 ]] && [[ "${RUST_MINOR}" -lt 86 ]]; }; then
    NEED_RUST_UPDATE="true"
  fi
else
  NEED_RUST_UPDATE="true"
fi

if [[ "${NEED_RUST_UPDATE}" == "true" ]]; then
  rustup toolchain install stable --profile minimal
  rustup default stable
fi

echo "Rustc: $(rustc --version)"
echo "Cargo: $(cargo --version)"

if [[ "${WITH_ANDROID}" == "true" ]]; then
  echo "==> [3/3] Adding Rust Android targets & verifying Android NDK..."
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -n "${ANDROID_NDK_LATEST_HOME:-}" && -d "${ANDROID_NDK_LATEST_HOME}" ]]; then
      export ANDROID_NDK_HOME="${ANDROID_NDK_LATEST_HOME}"
    elif [[ -n "${SDK_ROOT}" && -d "${SDK_ROOT}/ndk" ]] && [[ -n "$(ls -A "${SDK_ROOT}/ndk" 2>/dev/null || true)" ]]; then
      LATEST_NDK="$(ls -1 "${SDK_ROOT}/ndk" | sort -V | tail -n 1)"
      export ANDROID_NDK_HOME="${SDK_ROOT}/ndk/${LATEST_NDK}"
    elif [[ -n "${SDK_ROOT}" ]]; then
      SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
      if [[ ! -x "${SDKMANAGER}" ]]; then
        LATEST_CMD="$(find "${SDK_ROOT}/cmdline-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
        SDKMANAGER="${LATEST_CMD}/bin/sdkmanager"
      fi
      yes | "${SDKMANAGER}" --install "ndk;27.2.12479018" >/dev/null
      export ANDROID_NDK_HOME="${SDK_ROOT}/ndk/27.2.12479018"
    fi
  fi

  echo "Android NDK: ${ANDROID_NDK_HOME}"
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    echo "ANDROID_NDK_HOME=${ANDROID_NDK_HOME}" >> "${GITHUB_ENV}"
  fi
fi
