#!/usr/bin/env bash
# Kịch bản đồng bộ và biên dịch cây nguồn Chromium Content chính thức cho Android (yêu cầu máy trạm chuyên dụng >= 120 GB đĩa, >= 32 GB RAM).
# Tuân thủ phiên bản khóa cứng tại chromium/CHROMIUM_VERSION.lock.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCK_FILE="${REPO_ROOT}/chromium/CHROMIUM_VERSION.lock"

if [[ ! -f "${LOCK_FILE}" ]]; then
  echo "[ERROR] Missing ${LOCK_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${LOCK_FILE}"

WORK_DIR="${CHROMIUM_WORKSPACE_DIR:-${HOME}/chromium-veko}"
TARGET_CPU="${CHROMIUM_TARGET_CPU:-arm64}"

echo "================================================================="
echo " Veko Browser - Official Chromium Content Android Builder"
echo " Pinned Version : ${CHROMIUM_VERSION} (${CHROMIUM_GIT_COMMIT})"
echo " Target CPU     : ${TARGET_CPU}"
echo " Workspace      : ${WORK_DIR}"
echo "================================================================="

AVAIL_GB="$(df -BG "${REPO_ROOT}" | awk 'NR==2 {gsub("G","",$4); print $4}')"
if [[ "${AVAIL_GB}" -lt 100 ]]; then
  echo "[BLOCKER] Insufficient free disk space (${AVAIL_GB} GB available; >= 100 GB required for upstream Chromium //content + Blink + V8 checkout & out/Default)." >&2
  echo "          See docs/CHROMIUM_MIGRATION_AUDIT.md Section 3 for dedicated workstation / self-hosted runner setup." >&2
  exit 2
fi

if ! command -v gclient >/dev/null 2>&1; then
  echo "[ERROR] depot_tools (gclient/fetch/autoninja) is required in PATH." >&2
  exit 1
fi

mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

if [[ ! -d "${WORK_DIR}/src" ]]; then
  fetch --nohooks android
fi

cd "${WORK_DIR}/src"
git fetch origin "refs/tags/${CHROMIUM_VERSION}:refs/tags/${CHROMIUM_VERSION}" --depth=1
git checkout -f "${CHROMIUM_GIT_COMMIT}"
gclient sync --with_branch_heads --with_tags -D --no-history

# Gắn lớp nhúng C++20 của Veko vào cây nguồn //veko_embedder
mkdir -p "${WORK_DIR}/src/veko_embedder"
cp -rf "${REPO_ROOT}/chromium-native/chromium_src/"* "${WORK_DIR}/src/veko_embedder/"
cp -rf "${REPO_ROOT}/chromium-native/src/main/cpp/"* "${WORK_DIR}/src/veko_embedder/"
cp -rf "${REPO_ROOT}/privacy/rust/veko-adblock/include/"* "${WORK_DIR}/src/veko_embedder/"

gn gen "out/Veko_${TARGET_CPU}" --args="
  target_os=\"android\"
  target_cpu=\"${TARGET_CPU}\"
  is_debug=false
  is_component_build=false
  is_official_build=true
  symbol_level=0
  v8_symbol_level=0
  blink_symbol_level=0
  enable_nacl=false
  proprietary_codecs=false
"

autoninja -C "out/Veko_${TARGET_CPU}" content_shell_apk libcontent_shell_content_view.so
echo "[OK] Upstream Chromium Content artifacts built in ${WORK_DIR}/src/out/Veko_${TARGET_CPU}"
