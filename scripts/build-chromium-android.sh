#!/usr/bin/env bash
# Biên dịch tái lập (reproducible) tầng nhúng Chromium Content 156.0.8078.85 (0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5)
# bằng depot_tools + gclient + GN + Ninja trên máy Linux đủ tài nguyên (>= 16 vCPU, >= 32 GB RAM, >= 120 GB đĩa trống).
#
# Chế độ chạy:
#   --preflight-only : Kiểm tra khóa revision, đối chiếu upstream tag/SHA (nếu có mạng) và kiểm tra tài nguyên máy mà không tải 52 GB mã nguồn.
#   --abi=arm64-v8a  : Chọn ABI mục tiêu (mặc định: arm64-v8a; hỗ trợ: arm64-v8a, armeabi-v7a, x86_64).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCK_FILE="${REPO_ROOT}/chromium/CHROMIUM_VERSION.lock"

if [[ ! -f "${LOCK_FILE}" ]]; then
  echo "[ERROR] Missing lockfile: ${LOCK_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${LOCK_FILE}"

PREFLIGHT_ONLY="false"
ANDROID_ABI="${PRIMARY_ANDROID_ABI:-arm64-v8a}"

for arg in "$@"; do
  case "${arg}" in
    --preflight-only) PREFLIGHT_ONLY="true" ;;
    --abi=*) ANDROID_ABI="${arg#--abi=}" ;;
    *)
      echo "[ERROR] Unknown argument: ${arg}" >&2
      exit 1
      ;;
  esac
done

case "${ANDROID_ABI}" in
  arm64-v8a)
    GN_TARGET_CPU="arm64"
    RUST_TARGET="aarch64-linux-android"
    V8_SNAPSHOT_FILE="v8_context_snapshot_64.bin"
    ;;
  armeabi-v7a)
    GN_TARGET_CPU="arm"
    RUST_TARGET="armv7-linux-androideabi"
    V8_SNAPSHOT_FILE="v8_context_snapshot_32.bin"
    ;;
  x86_64)
    GN_TARGET_CPU="x64"
    RUST_TARGET="x86_64-linux-android"
    V8_SNAPSHOT_FILE="v8_context_snapshot_64.bin"
    ;;
  *)
    echo "[ERROR] Unsupported Android ABI: ${ANDROID_ABI}" >&2
    exit 1
    ;;
esac

WORK_DIR="${CHROMIUM_WORKSPACE_DIR:-${HOME}/chromium-veko}"
DIST_DIR="${CHROMIUM_DIST_DIR:-${REPO_ROOT}/out/chromium-sdk/${CHROMIUM_VERSION}/${ANDROID_ABI}}"
MIN_DISK_GB="${CHROMIUM_MIN_DISK_GB:-110}"
MIN_RAM_MB="${CHROMIUM_MIN_RAM_MB:-24576}"

echo "========================================================================"
echo " Veko Browser — Reproducible Chromium Content Android Builder"
echo " Pinned Version    : ${CHROMIUM_VERSION} (tag: ${CHROMIUM_GIT_TAG})"
echo " Pinned Commit SHA : ${CHROMIUM_COMMIT_SHA}"
echo " Branch Position   : ${CHROMIUM_BRANCH_POSITION}"
echo " Target ABI / CPU  : ${ANDROID_ABI} (GN target_cpu=${GN_TARGET_CPU})"
echo " Workspace         : ${WORK_DIR}"
echo " Output SDK Bundle : ${DIST_DIR}"
echo "========================================================================"

if [[ ! "${CHROMIUM_COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "[ERROR] CHROMIUM_COMMIT_SHA in lockfile is not a valid 40-char hex SHA: ${CHROMIUM_COMMIT_SHA}" >&2
  exit 1
fi

# Đối chiếu từ xa tag 156.0.8078.85 -> SHA chính thức khi có kết nối tới upstream hoặc GitHub mirror
REMOTE_SHA="$(git ls-remote "${CHROMIUM_UPSTREAM_REPO}" "refs/tags/${CHROMIUM_GIT_TAG}" 2>/dev/null | awk '{print $1}' || true)"
if [[ -z "${REMOTE_SHA}" ]]; then
  REMOTE_SHA="$(git ls-remote "${CHROMIUM_GITHUB_MIRROR}" "refs/tags/${CHROMIUM_GIT_TAG}" 2>/dev/null | awk '{print $1}' || true)"
fi
if [[ -n "${REMOTE_SHA}" ]]; then
  if [[ "${REMOTE_SHA}" != "${CHROMIUM_COMMIT_SHA}" ]]; then
    echo "[FATAL] Remote tag ${CHROMIUM_GIT_TAG} resolved to ${REMOTE_SHA}, expected ${CHROMIUM_COMMIT_SHA}!" >&2
    exit 1
  fi
  echo "[VERIFIED] Remote tag refs/tags/${CHROMIUM_GIT_TAG} == ${CHROMIUM_COMMIT_SHA}"
else
  echo "[WARN] Could not reach remote Git host via git ls-remote; relying on pinned lockfile SHA."
fi

mkdir -p "$(dirname "${WORK_DIR}")"
AVAIL_GB="$(df -BG "$(dirname "${WORK_DIR}")" | awk 'NR==2 {gsub("G","",$4); print $4}')"
TOTAL_RAM_MB="$(awk '/MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo)"
CPU_CORES="$(nproc)"

echo "[HOST] CPU=${CPU_CORES} cores | RAM=${TOTAL_RAM_MB} MiB | Free Disk=${AVAIL_GB} GiB"

if [[ "${PREFLIGHT_ONLY}" == "true" ]]; then
  if [[ "${AVAIL_GB}" -lt "${MIN_DISK_GB}" || "${TOTAL_RAM_MB}" -lt "${MIN_RAM_MB}" ]]; then
    echo "[PREFLIGHT_BLOCKED] Host does not meet full Chromium compile thresholds (need >= ${MIN_DISK_GB} GiB disk & >= ${MIN_RAM_MB} MiB RAM)."
    exit 0
  fi
  echo "[PREFLIGHT_READY] Host meets hardware requirements for full Chromium ${CHROMIUM_VERSION} (${ANDROID_ABI}) build."
  exit 0
fi

if [[ "${AVAIL_GB}" -lt "${MIN_DISK_GB}" ]]; then
  echo "[BLOCKER] Insufficient free disk space (${AVAIL_GB} GiB available; >= ${MIN_DISK_GB} GiB required for Chromium //content + Blink + V8)." >&2
  exit 2
fi

if [[ "${TOTAL_RAM_MB}" -lt "${MIN_RAM_MB}" ]]; then
  echo "[BLOCKER] Insufficient system RAM (${TOTAL_RAM_MB} MiB available; >= ${MIN_RAM_MB} MiB required to link libveko_content_embedder.so without LLD OOM)." >&2
  exit 2
fi

if ! command -v gclient >/dev/null 2>&1; then
  bash "${SCRIPT_DIR}/bootstrap-depot-tools.sh"
  export PATH="${DEPOT_TOOLS_DIR:-${HOME}/depot_tools}:${PATH}"
fi

mkdir -p "${WORK_DIR}"
cat > "${WORK_DIR}/.gclient" <<EOF
solutions = [
  {
    "name": "src",
    "url": "${CHROMIUM_UPSTREAM_REPO}",
    "managed": False,
    "custom_deps": {},
    "custom_vars": {
      "checkout_android": True,
      "checkout_telemetry_dependencies": False,
    },
  },
]
target_os = ["android"]
EOF

cd "${WORK_DIR}"
if [[ ! -d "${WORK_DIR}/src/.git" ]]; then
  mkdir -p "${WORK_DIR}/src"
  git -C "${WORK_DIR}/src" init
  git -C "${WORK_DIR}/src" remote add origin "${CHROMIUM_UPSTREAM_REPO}"
fi

echo "==> Fetching exact Chromium tag ${CHROMIUM_GIT_TAG} (${CHROMIUM_COMMIT_SHA})..."
git -C "${WORK_DIR}/src" fetch --depth=1 origin "refs/tags/${CHROMIUM_GIT_TAG}:refs/tags/${CHROMIUM_GIT_TAG}"
git -C "${WORK_DIR}/src" checkout -f "${CHROMIUM_COMMIT_SHA}"

ACTUAL_HEAD="$(git -C "${WORK_DIR}/src" rev-parse HEAD)"
if [[ "${ACTUAL_HEAD}" != "${CHROMIUM_COMMIT_SHA}" ]]; then
  echo "[FATAL] Checked out HEAD (${ACTUAL_HEAD}) != pinned CHROMIUM_COMMIT_SHA (${CHROMIUM_COMMIT_SHA})." >&2
  exit 1
fi

echo "==> Running gclient sync for Android sysroot, NDK (${CHROMIUM_UPSTREAM_NDK_VERSION}), Clang & third_party..."
gclient sync --with_branch_heads --with_tags --no-history -D

echo "==> Staging //veko_embedder C++20 sources & BUILD.gn into Chromium tree..."
EMBEDDER_DIR="${WORK_DIR}/src/veko_embedder"
rm -rf "${EMBEDDER_DIR}"
mkdir -p "${EMBEDDER_DIR}"
cp -f "${REPO_ROOT}/chromium/veko_embedder/BUILD.gn" "${EMBEDDER_DIR}/BUILD.gn"
cp -f "${REPO_ROOT}/chromium-native/chromium_src/"* "${EMBEDDER_DIR}/"
cp -f "${REPO_ROOT}/chromium-native/src/main/cpp/"* "${EMBEDDER_DIR}/"
cp -f "${REPO_ROOT}/privacy/rust/veko-adblock/include/"* "${EMBEDDER_DIR}/"

OUT_REL="out/Veko_${GN_TARGET_CPU}"
OUT_ABS="${WORK_DIR}/src/${OUT_REL}"
mkdir -p "${OUT_ABS}/veko_rust"

echo "==> Building Rust adblock-rust (${ADBLOCK_RUST_VERSION}) static library for ${RUST_TARGET} using Chromium NDK..."
CHROMIUM_NDK="${WORK_DIR}/src/third_party/android_toolchain/ndk"
NDK_BIN="${CHROMIUM_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
case "${ANDROID_ABI}" in
  arm64-v8a) CLANG_TRIPLE="aarch64-linux-android${CHROMIUM_UPSTREAM_MIN_SDK}" ;;
  armeabi-v7a) CLANG_TRIPLE="armv7a-linux-androideabi${CHROMIUM_UPSTREAM_MIN_SDK}" ;;
  x86_64) CLANG_TRIPLE="x86_64-linux-android${CHROMIUM_UPSTREAM_MIN_SDK}" ;;
esac
UPPER_RUST_TARGET="$(echo "${RUST_TARGET}" | tr '[:lower:]-' '[:upper:]_')"
(
  cd "${REPO_ROOT}/privacy/rust/veko-adblock"
  rustup target add "${RUST_TARGET}"
  export "CARGO_TARGET_${UPPER_RUST_TARGET}_LINKER=${NDK_BIN}/${CLANG_TRIPLE}-clang"
  export "CC_${RUST_TARGET//-/_}=${NDK_BIN}/${CLANG_TRIPLE}-clang"
  export "AR_${RUST_TARGET//-/_}=${NDK_BIN}/llvm-ar"
  cargo build --release --locked --target "${RUST_TARGET}"
  cp -f "target/${RUST_TARGET}/release/libveko_adblock_ffi.a" "${OUT_ABS}/veko_rust/libveko_adblock_ffi.a"
)

cd "${WORK_DIR}/src"
GN_ARGS="
  target_os=\"android\"
  target_cpu=\"${GN_TARGET_CPU}\"
  default_min_sdk_version=${CHROMIUM_UPSTREAM_MIN_SDK}
  is_debug=false
  is_component_build=false
  is_official_build=true
  chrome_pgo_phase=0
  symbol_level=0
  blink_symbol_level=0
  v8_symbol_level=0
  android_static_analysis=\"off\"
  enable_nacl=false
  proprietary_codecs=false
  enable_remoting=false
  enable_vr=false
"

echo "==> Generating Ninja files in ${OUT_REL}..."
gn gen "${OUT_REL}" --args="${GN_ARGS}"

echo "==> Building //veko_embedder:libveko_content_embedder & //veko_embedder:veko_content_assets..."
autoninja -C "${OUT_REL}" \
  //veko_embedder:libveko_content_embedder \
  //veko_embedder:veko_content_assets \
  //veko_embedder:veko_embedder_java \
  //components/crash/core/app:chrome_crashpad_handler_named_as_so

echo "==> Packaging verified SDK bundle with provenance into ${DIST_DIR}..."
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/jni/${ANDROID_ABI}" "${DIST_DIR}/assets" "${DIST_DIR}/java"

cp -f "${OUT_ABS}/libveko_content_embedder.so" "${DIST_DIR}/jni/${ANDROID_ABI}/libveko_content_embedder.so"
cp -f "${OUT_ABS}/libchrome_crashpad_handler.so" "${DIST_DIR}/jni/${ANDROID_ABI}/libchrome_crashpad_handler.so"
cp -f "${OUT_ABS}/veko_content.pak" "${DIST_DIR}/assets/veko_content.pak"
cp -f "${OUT_ABS}/icudtl.dat" "${DIST_DIR}/assets/icudtl.dat"
if [[ -f "${OUT_ABS}/assets/${V8_SNAPSHOT_FILE}" ]]; then
  cp -f "${OUT_ABS}/assets/${V8_SNAPSHOT_FILE}" "${DIST_DIR}/assets/${V8_SNAPSHOT_FILE}"
elif [[ -f "${OUT_ABS}/snapshot_blob.bin" ]]; then
  cp -f "${OUT_ABS}/snapshot_blob.bin" "${DIST_DIR}/assets/snapshot_blob.bin"
fi

# Kiểm tra tính hợp lệ ELF và căn chỉnh trang nhớ 16 KB ngay trên thư viện vừa biên dịch
python3 "${SCRIPT_DIR}/verify-elf-packaging.py" \
  --abi "${ANDROID_ABI}" \
  --require-page-size 16384 \
  "${DIST_DIR}/jni/${ANDROID_ABI}/libveko_content_embedder.so"

cat > "${DIST_DIR}/PROVENANCE.json" <<EOF
{
  "chromiumVersion": "${CHROMIUM_VERSION}",
  "chromiumTag": "${CHROMIUM_GIT_TAG}",
  "chromiumCommit": "${ACTUAL_HEAD}",
  "branchPosition": "${CHROMIUM_BRANCH_POSITION}",
  "androidAbi": "${ANDROID_ABI}",
  "gnTargetCpu": "${GN_TARGET_CPU}",
  "minSdkVersion": ${CHROMIUM_UPSTREAM_MIN_SDK},
  "adblockRustVersion": "${ADBLOCK_RUST_VERSION}",
  "builtAtUtc": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

(
  cd "${DIST_DIR}"
  find jni assets java PROVENANCE.json -type f | sort | xargs sha256sum > SHA256SUMS.txt
  sha256sum --check --strict SHA256SUMS.txt
)

ARCHIVE_PATH="${DIST_DIR}/../veko-chromium-sdk-${CHROMIUM_VERSION}-${ANDROID_ABI}.tar.gz"
tar -C "${DIST_DIR}" -czf "${ARCHIVE_PATH}" .
sha256sum "${ARCHIVE_PATH}" > "${ARCHIVE_PATH}.sha256"

echo "[OK] Chromium Content ${CHROMIUM_VERSION} (${ANDROID_ABI}) SDK packaged at ${ARCHIVE_PATH}"
