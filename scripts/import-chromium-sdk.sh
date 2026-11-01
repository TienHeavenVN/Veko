#!/usr/bin/env bash
# Nhập và xác thực nghiêm ngặt gói nhị phân veko-chromium-sdk (được build tái lập từ scripts/build-chromium-android.sh)
# vào cây dự án Android trước khi đóng gói APK. Tuyệt đối từ chối mọi file .so giả lập hoặc sai commit SHA.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCK_FILE="${REPO_ROOT}/chromium/CHROMIUM_VERSION.lock"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <path-to-extracted-sdk-dir-or-tar.gz>" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${LOCK_FILE}"

INPUT_PATH="$1"
TMP_EXTRACT=""
cleanup() {
  if [[ -n "${TMP_EXTRACT}" && -d "${TMP_EXTRACT}" ]]; then
    rm -rf "${TMP_EXTRACT}"
  fi
}
trap cleanup EXIT

if [[ -f "${INPUT_PATH}" ]]; then
  TMP_EXTRACT="$(mktemp -d)"
  tar -C "${TMP_EXTRACT}" -xzf "${INPUT_PATH}"
  SDK_DIR="${TMP_EXTRACT}"
elif [[ -d "${INPUT_PATH}" ]]; then
  SDK_DIR="${INPUT_PATH}"
else
  echo "[ERROR] SDK input not found: ${INPUT_PATH}" >&2
  exit 1
fi

if [[ ! -f "${SDK_DIR}/PROVENANCE.json" || ! -f "${SDK_DIR}/SHA256SUMS.txt" ]]; then
  echo "[ERROR] Missing PROVENANCE.json or SHA256SUMS.txt in SDK bundle. Refusing untrusted binary." >&2
  exit 1
fi

echo "==> [1/4] Verifying SHA-256 checksums of all SDK artifacts..."
(
  cd "${SDK_DIR}"
  sha256sum --check --strict SHA256SUMS.txt
)

echo "==> [2/4] Verifying PROVENANCE.json against chromium/CHROMIUM_VERSION.lock..."
ABI="$(python3 - "${SDK_DIR}/PROVENANCE.json" "${CHROMIUM_VERSION}" "${CHROMIUM_COMMIT_SHA}" <<'PY'
import json, sys
prov_path, expected_ver, expected_sha = sys.argv[1], sys.argv[2], sys.argv[3]
with open(prov_path, "r", encoding="utf-8") as f:
    prov = json.load(f)
if prov.get("chromiumVersion") != expected_ver:
    raise SystemExit(f"Version mismatch: {prov.get('chromiumVersion')} != {expected_ver}")
if prov.get("chromiumCommit") != expected_sha:
    raise SystemExit(f"Commit SHA mismatch: {prov.get('chromiumCommit')} != {expected_sha}")
abi = prov.get("androidAbi", "")
if abi not in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    raise SystemExit(f"Invalid Android ABI in provenance: {abi}")
print(abi)
PY
)"

EMBEDDER_SO="${SDK_DIR}/jni/${ABI}/${CHROMIUM_EMBEDDER_SHARED_LIB}"
CRASHPAD_SO="${SDK_DIR}/jni/${ABI}/${CHROMIUM_CRASHPAD_HANDLER_LIB}"
PAK_FILE="${SDK_DIR}/assets/veko_content.pak"
ICU_FILE="${SDK_DIR}/assets/icudtl.dat"

for req in "${EMBEDDER_SO}" "${CRASHPAD_SO}" "${PAK_FILE}" "${ICU_FILE}"; do
  if [[ ! -s "${req}" ]]; then
    echo "[ERROR] Mandatory Chromium Content artifact missing or empty: ${req}" >&2
    exit 1
  fi
done

echo "==> [3/4] Auditing ELF headers, DT_NEEDED & 16 KB page alignment for ${ABI}..."
python3 "${SCRIPT_DIR}/verify-elf-packaging.py" \
  --abi "${ABI}" \
  --require-page-size "${ANDROID_ELF_MAX_PAGE_SIZE}" \
  --require-symbol "JNI_OnLoad" \
  "${EMBEDDER_SO}"

python3 "${SCRIPT_DIR}/verify-elf-packaging.py" \
  --abi "${ABI}" \
  --require-page-size "${ANDROID_ELF_MAX_PAGE_SIZE}" \
  "${CRASHPAD_SO}"

echo "==> [4/4] Staging verified Chromium ${CHROMIUM_VERSION} (${ABI}) artifacts intochromium-embedder..."
DEST_JNI="${REPO_ROOT}/chromium-embedder/prebuilt/jniLibs/${ABI}"
DEST_ASSETS="${REPO_ROOT}/chromium-embedder/prebuilt/assets"
mkdir -p "${DEST_JNI}" "${DEST_ASSETS}"

cp -f "${EMBEDDER_SO}" "${DEST_JNI}/${CHROMIUM_EMBEDDER_SHARED_LIB}"
cp -f "${CRASHPAD_SO}" "${DEST_JNI}/${CHROMIUM_CRASHPAD_HANDLER_LIB}"
cp -f "${PAK_FILE}" "${DEST_ASSETS}/veko_content.pak"
cp -f "${ICU_FILE}" "${DEST_ASSETS}/icudtl.dat"
find "${SDK_DIR}/assets" -maxdepth 1 -name "v8_context_snapshot*.bin" -exec cp -f {} "${DEST_ASSETS}/" \;
cp -f "${SDK_DIR}/PROVENANCE.json" "${REPO_ROOT}/chromium-embedder/prebuilt/PROVENANCE_${ABI}.json"

echo "[OK] Staged verified Chromium Content ${CHROMIUM_VERSION} (${ABI}) into chromium-embedder/prebuilt/."
