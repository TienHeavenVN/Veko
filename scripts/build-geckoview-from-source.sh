#!/usr/bin/env bash
# Biên dịch Mozilla GeckoView 150.0.3 trực tiếp từ mã nguồn Firefox upstream (`MozFennec`)
# phục vụ đóng gói F-Droid hoàn toàn từ nguồn (tuyệt đối không dùng AAR dựng sẵn từ maven.mozilla.org).
#
# Nguồn chuẩn đã ghim (Pinned Upstream Source):
#   - Repository : https://github.com/mozilla-firefox/firefox (`srclibs/MozFennec.yml`)
#   - Git Tag    : FIREFOX-ANDROID_150_0_3_RELEASE
#   - Commit SHA : a077abc2b0f43ed7cc59a8bfcd873e683500d23a
#   - Milestone  : 150.0.3 (`config/milestone.txt`)
#   - NDK chuẩn  : r29 (`29.0.14206865`, theo `python/mozboot/mozboot/android.py`)
#   - Chế độ     : `--enable-project=mobile/android --enable-geckoview-lite`
#                  (xuất bản `org.mozilla.geckoview:geckoview:150.0.<BUILD_ID>` + `geckoview-exoplayer2`
#                   vào `mavenLocal()` mà không kéo Glean/AppServices `-omni` prebuilt).
set -euo pipefail

EXPECTED_MOZFENNEC_TAG="FIREFOX-ANDROID_150_0_3_RELEASE"
EXPECTED_MOZFENNEC_COMMIT="a077abc2b0f43ed7cc59a8bfcd873e683500d23a"
EXPECTED_MILESTONE="150.0.3"
EXPECTED_NDK_VERSION="29.0.14206865"
DEFAULT_BUILD_ID="20260511200624"
MIN_RAM_PLUS_SWAP_MB=12288
MIN_DISK_FREE_GB=35

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

MOZFENNEC_DIR="${MOZFENNEC_DIR:-${REPO_ROOT}/../srclib/MozFennec}"
ABIS_CSV="arm64-v8a,armeabi-v7a,x86_64"
BUILD_ID="${MOZ_BUILD_DATE:-${DEFAULT_BUILD_ID}}"
MAVEN_LOCAL_REPO="${MAVEN_LOCAL_REPO:-${HOME}/.m2/repository}"
WASI_SYSROOT="${WASI_SYSROOT:-}"
MICROG_FIDO_VERSION="${MICROG_FIDO_VERSION:-0.3.13.223716}"
REPLACE_GMS_FIDO_WITH_MICROG=true
ALLOW_UNPINNED_COMMIT=false
ALLOW_LOW_RESOURCES=false
CHECK_ENV_ONLY=false

for arg in "$@"; do
  case "${arg}" in
    --mozfennec-dir=*)
      MOZFENNEC_DIR="${arg#*=}"
      ;;
    --abis=*)
      ABIS_CSV="${arg#*=}"
      ;;
    --build-id=*)
      BUILD_ID="${arg#*=}"
      ;;
    --maven-local=*)
      MAVEN_LOCAL_REPO="${arg#*=}"
      ;;
    --wasi-sysroot=*)
      WASI_SYSROOT="${arg#*=}"
      ;;
    --microg-fido-version=*)
      MICROG_FIDO_VERSION="${arg#*=}"
      ;;
    --keep-gms-fido)
      REPLACE_GMS_FIDO_WITH_MICROG=false
      ;;
    --allow-unpinned-commit)
      ALLOW_UNPINNED_COMMIT=true
      ;;
    --allow-low-resources)
      ALLOW_LOW_RESOURCES=true
      ;;
    --check-env)
      CHECK_ENV_ONLY=true
      ;;
    --help|-h)
      cat <<'EOF'
Usage: bash scripts/build-geckoview-from-source.sh [OPTIONS]

Options:
  --mozfennec-dir=PATH      Path to MozFennec checkout (default: ../srclib/MozFennec)
  --abis=CSV                Target Android ABIs (default: arm64-v8a,armeabi-v7a,x86_64)
  --build-id=YYYYMMDDHHMMSS Deterministic MOZ_BUILD_DATE (default: 20260511200624 -> 150.0.20260511200624)
  --maven-local=PATH        Local Maven repository output (default: ~/.m2/repository)
  --wasi-sysroot=PATH       Optional WASI sysroot path (defaults to --without-wasm-sandboxed-libraries)
  --allow-unpinned-commit   Allow building a MozFennec commit other than a077abc2b0f43ed7cc59a8bfcd873e683500d23a
  --allow-low-resources     Bypass minimum RAM+Swap (12 GiB) and free disk (35 GiB) safety gate
  --check-env               Probe host resources, toolchains & MozFennec source without compiling
EOF
      exit 0
      ;;
    *)
      echo "[BUILD_SCRIPT_ERROR] Unknown option: ${arg}" >&2
      exit 2
      ;;
  esac
done

IFS=',' read -r -a REQUESTED_ABIS <<< "${ABIS_CSV}"
if [[ "${#REQUESTED_ABIS[@]}" -eq 0 ]]; then
  echo "[BUILD_SCRIPT_ERROR] --abis must specify at least one ABI (arm64-v8a, armeabi-v7a, x86_64)." >&2
  exit 2
fi

abi_to_gecko_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "arm-linux-androideabi" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *)
      echo "[BUILD_SCRIPT_ERROR] Unsupported Android ABI '$1' (expected arm64-v8a | armeabi-v7a | x86_64)" >&2
      return 1
      ;;
  esac
}

abi_to_rust_targets() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7-linux-androideabi thumbv7neon-linux-androideabi" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *) return 1 ;;
  esac
}

for abi in "${REQUESTED_ABIS[@]}"; do
  abi_to_gecko_target "${abi}" >/dev/null
done

echo "=== GeckoView 150.0.3 Source Build Preflight (MozFennec@${EXPECTED_MOZFENNEC_TAG}) ==="
PREFLIGHT_FAILURES=0

# 1. Kiểm tra tài nguyên phần cứng thực tế (RAM + Swap + Disk)
MEM_TOTAL_MB=$(awk '/^MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo 2>/dev/null || echo 0)
SWAP_TOTAL_MB=$(awk '/^SwapTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo 2>/dev/null || echo 0)
RAM_PLUS_SWAP_MB=$(( MEM_TOTAL_MB + SWAP_TOTAL_MB ))
CPU_CORES=$(nproc 2>/dev/null || echo 1)
DISK_PROBE_PATH="${REPO_ROOT}"
if [[ -d "${MOZFENNEC_DIR}" ]]; then
  DISK_PROBE_PATH="${MOZFENNEC_DIR}"
fi
DISK_FREE_GB=$(df -BG "${DISK_PROBE_PATH}" | awk 'NR==2 {gsub(/G/, "", $4); print $4}')

echo "[INFO] Host resources: CPU=${CPU_CORES} vCPU | RAM=${MEM_TOTAL_MB} MiB | Swap=${SWAP_TOTAL_MB} MiB | Free Disk=${DISK_FREE_GB} GiB"
if [[ "${ALLOW_LOW_RESOURCES}" != "true" ]]; then
  if (( RAM_PLUS_SWAP_MB < MIN_RAM_PLUS_SWAP_MB )); then
    echo "[RESOURCE_LIMIT] Insufficient memory for linking libxul.so: RAM+Swap=${RAM_PLUS_SWAP_MB} MiB (< ${MIN_RAM_PLUS_SWAP_MB} MiB minimum)." >&2
    PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
  fi
  if (( DISK_FREE_GB < MIN_DISK_FREE_GB )); then
    echo "[RESOURCE_LIMIT] Insufficient free disk space for MozFennec source + multi-ABI objdirs: ${DISK_FREE_GB} GiB (< ${MIN_DISK_FREE_GB} GiB minimum)." >&2
    PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
  fi
fi

# 2. Kiểm tra chuỗi công cụ biên dịch bắt buộc trên máy chủ
REQUIRED_CMDS=(git python3 java javac rustc cargo clang clang++ ld.lld llvm-config cmake ninja pkg-config make unzip zip)
for cmd in "${REQUIRED_CMDS[@]}"; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "[TOOLCHAIN_ERROR] Required build tool '${cmd}' is not installed in PATH." >&2
    PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
  fi
done

if command -v rustup >/dev/null 2>&1; then
  INSTALLED_RUST_TARGETS=$(rustup target list --installed 2>/dev/null || true)
  for abi in "${REQUESTED_ABIS[@]}"; do
    for rt in $(abi_to_rust_targets "${abi}"); do
      if ! grep -qx "${rt}" <<< "${INSTALLED_RUST_TARGETS}"; then
        echo "[TOOLCHAIN_ERROR] Missing Rust cross-target '${rt}' for ABI '${abi}' (run: rustup target add ${rt})." >&2
        PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
      fi
    done
  done
fi

# 3. Kiểm tra Android SDK & Android NDK r29 (29.0.14206865)
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${SDK_ROOT}" || ! -d "${SDK_ROOT}" ]]; then
  echo "[DEPENDENCY_ERROR] ANDROID_HOME / ANDROID_SDK_ROOT is not set or directory does not exist." >&2
  PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
fi

NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "${NDK_ROOT}" && -n "${SDK_ROOT}" && -d "${SDK_ROOT}/ndk/${EXPECTED_NDK_VERSION}" ]]; then
  NDK_ROOT="${SDK_ROOT}/ndk/${EXPECTED_NDK_VERSION}"
fi
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "[DEPENDENCY_ERROR] Android NDK r29 (${EXPECTED_NDK_VERSION}) not found. Set ANDROID_NDK_ROOT or install ndk;${EXPECTED_NDK_VERSION}." >&2
  PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
elif [[ -f "${NDK_ROOT}/source.properties" ]]; then
  ACTUAL_NDK_REV=$(awk -F'=' '/Pkg.Revision/ {gsub(/[[:space:]]/, "", $2); print $2}' "${NDK_ROOT}/source.properties")
  echo "[INFO] Detected Android NDK: ${NDK_ROOT} (Pkg.Revision=${ACTUAL_NDK_REV})"
fi

# 4. Kiểm tra mã nguồn MozFennec và xác thực commit SHA + milestone 150.0.3
if [[ ! -d "${MOZFENNEC_DIR}" || ! -f "${MOZFENNEC_DIR}/mach" ]]; then
  echo "[SOURCE_ERROR] MozFennec source tree not found at '${MOZFENNEC_DIR}' (expected srclibs MozFennec@${EXPECTED_MOZFENNEC_TAG})." >&2
  PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
else
  MOZFENNEC_DIR="$(cd "${MOZFENNEC_DIR}" && pwd)"
  ACTUAL_MILESTONE=$(grep -v '^#' "${MOZFENNEC_DIR}/config/milestone.txt" | tr -d '[:space:]')
  if [[ "${ACTUAL_MILESTONE}" != "${EXPECTED_MILESTONE}" ]]; then
    echo "[SOURCE_ERROR] MozFennec milestone mismatch: expected '${EXPECTED_MILESTONE}', found '${ACTUAL_MILESTONE}'." >&2
    PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
  fi
  if [[ -d "${MOZFENNEC_DIR}/.git" ]]; then
    ACTUAL_COMMIT=$(git -C "${MOZFENNEC_DIR}" rev-parse HEAD)
    echo "[INFO] MozFennec HEAD commit: ${ACTUAL_COMMIT} (expected ${EXPECTED_MOZFENNEC_COMMIT})"
    if [[ "${ALLOW_UNPINNED_COMMIT}" != "true" && "${ACTUAL_COMMIT}" != "${EXPECTED_MOZFENNEC_COMMIT}" ]]; then
      echo "[SOURCE_ERROR] MozFennec commit mismatch: expected ${EXPECTED_MOZFENNEC_COMMIT} (${EXPECTED_MOZFENNEC_TAG}), got ${ACTUAL_COMMIT}." >&2
      PREFLIGHT_FAILURES=$(( PREFLIGHT_FAILURES + 1 ))
    fi
  fi
fi

if (( PREFLIGHT_FAILURES > 0 )); then
  echo "[ERROR] Preflight failed with ${PREFLIGHT_FAILURES} blocker(s). Refusing to fake or bypass GeckoView source compilation." >&2
  exit 1
fi

if [[ "${CHECK_ENV_ONLY}" == "true" ]]; then
  echo "[OK] All host resources, toolchains, SDK/NDK, and MozFennec@${EXPECTED_MOZFENNEC_TAG} source checks passed."
  exit 0
fi

# 5. Thiết lập biến môi trường tất định & loại bỏ hoàn toàn maven.mozilla.org trong Gradle nội bộ của MozFennec
export MOZILLA_OFFICIAL=1
export MOZ_AUTOMATION=1
export MOZ_TELEMETRY_REPORTING=
export MOZ_BUILD_DATE="${BUILD_ID}"
export MACH_BUILD_PYTHON_NATIVE_PACKAGE_SOURCE=none
export MOZBUILD_STATE_PATH="${MOZBUILD_STATE_PATH:-${MOZFENNEC_DIR}/.mozbuild-state}"
export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export ANDROID_NDK_ROOT="${NDK_ROOT}"
export GRADLE_MAVEN_REPOSITORIES="${GRADLE_MAVEN_REPOSITORIES:-https://maven.google.com/,https://repo.maven.apache.org/maven2/,https://plugins.gradle.org/m2/}"
mkdir -p "${MOZBUILD_STATE_PATH}" "${MAVEN_LOCAL_REPO}"

# Thay thế com.google.android.gms:play-services-fido độc quyền trong MozFennec bằng thư viện nguồn mở
# org.microg.gms:play-services-fido (Apache-2.0 trên Maven Central — tương tự công thức org.mozilla.fennec_fdroid trên fdroiddata)
if [[ "${REPLACE_GMS_FIDO_WITH_MICROG}" == "true" && -f "${MOZFENNEC_DIR}/gradle/libs.versions.toml" ]]; then
  python3 - "${MOZFENNEC_DIR}/gradle/libs.versions.toml" "${MICROG_FIDO_VERSION}" <<'PY'
import pathlib, re, sys
toml_path = pathlib.Path(sys.argv[1])
microg_ver = sys.argv[2]
text = toml_path.read_text(encoding="utf-8")
updated = re.sub(
    r'com\.google\.android\.gms:play-services-fido(?::[^\s"\']+)?',
    f"org.microg.gms:play-services-fido:{microg_ver}",
    text,
)
updated = re.sub(
    r'(group\s*=\s*)"com\.google\.android\.gms"(\s*,\s*name\s*=\s*"play-services-fido")',
    r'\1"org.microg.gms"\2',
    updated,
)
if updated != text:
    toml_path.write_text(updated, encoding="utf-8")
    print(f"[OK] Replaced proprietary com.google.android.gms:play-services-fido with FOSS org.microg.gms:play-services-fido:{microg_ver} in {toml_path}")
PY
fi

write_common_mozconfig() {
  local out_file="$1"
  local obj_dir="$2"
  local gecko_target="$3"
  cat > "${out_file}" <<EOF
mk_add_options MOZ_OBJDIR=${obj_dir}
mk_add_options "export MOZ_BUILD_DATE=${BUILD_ID}"
mk_add_options "export MOZILLA_OFFICIAL=1"
ac_add_options --enable-project=mobile/android
ac_add_options --enable-geckoview-lite
ac_add_options --target=${gecko_target}
ac_add_options --enable-release
ac_add_options --enable-update-channel=release
ac_add_options --disable-debug
ac_add_options --disable-tests
ac_add_options --disable-crashreporter
ac_add_options --disable-updater
ac_add_options --disable-bootstrap
ac_add_options --with-android-sdk=${SDK_ROOT}
ac_add_options --with-android-ndk=${NDK_ROOT}
EOF
  if [[ -n "${WASI_SYSROOT}" ]]; then
    echo "ac_add_options --with-wasi-sysroot=${WASI_SYSROOT}" >> "${out_file}"
  else
    echo "ac_add_options --without-wasm-sandboxed-libraries" >> "${out_file}"
  fi
}

# 6. Biên dịch tuần tự từng kiến trúc ABI từ mã nguồn C++/Rust & đóng gói target.maven.zip cục bộ
declare -A ABI_MAVEN_ZIPS
for abi in "${REQUESTED_ABIS[@]}"; do
  gecko_target="$(abi_to_gecko_target "${abi}")"
  obj_dir="${MOZFENNEC_DIR}/obj-veko-${abi}"
  mozcfg="${MOZFENNEC_DIR}/mozconfig-veko-${abi}"
  write_common_mozconfig "${mozcfg}" "${obj_dir}" "${gecko_target}"

  echo "=== [MozFennec] Compiling GeckoView 150.0.3 from source for ABI=${abi} (${gecko_target}) ==="
  (
    cd "${MOZFENNEC_DIR}"
    export MOZCONFIG="${mozcfg}"
    python3 ./mach --no-interactive configure
    python3 ./mach --no-interactive build
    python3 ./mach --no-interactive package
    python3 ./mach --no-interactive android archive-geckoview --no-configuration-cache
  )

  maven_zip="${obj_dir}/gradle/target.maven.zip"
  if [[ ! -s "${maven_zip}" ]]; then
    echo "[BUILD_SCRIPT_ERROR] Expected per-ABI Maven archive missing at ${maven_zip}" >&2
    exit 1
  fi
  ABI_MAVEN_ZIPS["${abi}"]="${maven_zip}"
done

# 7. Hợp nhất Fat-AAR đa kiến trúc (`org.mozilla.geckoview:geckoview:150.0.<BUILD_ID>`) và publish vào mavenLocal()
PRIMARY_ABI="${REQUESTED_ABIS[0]}"
PRIMARY_TARGET="$(abi_to_gecko_target "${PRIMARY_ABI}")"
FAT_OBJDIR="${MOZFENNEC_DIR}/obj-veko-fat-aar"
FAT_MOZCFG="${MOZFENNEC_DIR}/mozconfig-veko-fat-aar"
write_common_mozconfig "${FAT_MOZCFG}" "${FAT_OBJDIR}" "${PRIMARY_TARGET}"

{
  echo "export MOZ_ANDROID_FAT_AAR_ARCHITECTURES=\"${ABIS_CSV}\""
  if [[ -n "${ABI_MAVEN_ZIPS[arm64-v8a]:-}" ]]; then
    echo "export MOZ_ANDROID_FAT_AAR_ARM64_V8A=\"${ABI_MAVEN_ZIPS[arm64-v8a]}\""
  fi
  if [[ -n "${ABI_MAVEN_ZIPS[armeabi-v7a]:-}" ]]; then
    echo "export MOZ_ANDROID_FAT_AAR_ARMEABI_V7A=\"${ABI_MAVEN_ZIPS[armeabi-v7a]}\""
  fi
  if [[ -n "${ABI_MAVEN_ZIPS[x86_64]:-}" ]]; then
    echo "export MOZ_ANDROID_FAT_AAR_X86_64=\"${ABI_MAVEN_ZIPS[x86_64]}\""
  fi
} >> "${FAT_MOZCFG}"

echo "=== [MozFennec] Assembling multi-ABI GeckoView AAR (${ABIS_CSV}) & publishing to mavenLocal (${MAVEN_LOCAL_REPO}) ==="
(
  cd "${MOZFENNEC_DIR}"
  export MOZCONFIG="${FAT_MOZCFG}"
  export MOZ_ANDROID_FAT_AAR_ARCHITECTURES="${ABIS_CSV}"
  [[ -n "${ABI_MAVEN_ZIPS[arm64-v8a]:-}" ]] && export MOZ_ANDROID_FAT_AAR_ARM64_V8A="${ABI_MAVEN_ZIPS[arm64-v8a]}"
  [[ -n "${ABI_MAVEN_ZIPS[armeabi-v7a]:-}" ]] && export MOZ_ANDROID_FAT_AAR_ARMEABI_V7A="${ABI_MAVEN_ZIPS[armeabi-v7a]}"
  [[ -n "${ABI_MAVEN_ZIPS[x86_64]:-}" ]] && export MOZ_ANDROID_FAT_AAR_X86_64="${ABI_MAVEN_ZIPS[x86_64]}"

  python3 ./mach --no-interactive configure
  python3 ./mach --no-interactive build stage-package
  make -C "${FAT_OBJDIR}" recurse_android-fat-aar-artifact
  python3 ./mach --no-interactive gradle \
    geckoview:publishDebugPublicationToMavenLocal \
    exoplayer2:publishDebugPublicationToMavenLocal \
    "-Dmaven.repo.local=${MAVEN_LOCAL_REPO}" \
    --no-configuration-cache
)

# 8. Xác minh tệp AAR, POM, các thư viện ELF `.so` theo từng ABI và in bảng băm SHA-256
GECKOVIEW_VERSION="150.0.${BUILD_ID}"
AAR_PATH="${MAVEN_LOCAL_REPO}/org/mozilla/geckoview/geckoview/${GECKOVIEW_VERSION}/geckoview-${GECKOVIEW_VERSION}.aar"
POM_PATH="${MAVEN_LOCAL_REPO}/org/mozilla/geckoview/geckoview/${GECKOVIEW_VERSION}/geckoview-${GECKOVIEW_VERSION}.pom"

python3 - "${AAR_PATH}" "${POM_PATH}" "${ABIS_CSV}" <<'PY'
import hashlib, pathlib, sys, zipfile

aar_path = pathlib.Path(sys.argv[1])
pom_path = pathlib.Path(sys.argv[2])
abis = [a.strip() for a in sys.argv[3].split(",") if a.strip()]
required_sos = (
    "libxul.so",
    "libmozglue.so",
    "libnss3.so",
    "liblgpllibs.so",
    "libfreebl3.so",
    "libsoftokn3.so",
    "libipcclientcerts.so",
)

if not aar_path.is_file() or aar_path.stat().st_size == 0:
    raise SystemExit(f"[BUILD_SCRIPT_ERROR] Missing or empty GeckoView AAR: {aar_path}")
if not pom_path.is_file() or pom_path.stat().st_size == 0:
    raise SystemExit(f"[BUILD_SCRIPT_ERROR] Missing or empty GeckoView POM: {pom_path}")

with zipfile.ZipFile(aar_path, "r") as zf:
    entries = set(zf.namelist())
    if "classes.jar" not in entries or "assets/omni.ja" not in entries:
        raise SystemExit(f"[BUILD_SCRIPT_ERROR] Incomplete GeckoView AAR (missing classes.jar or assets/omni.ja): {aar_path}")
    for abi in abis:
        for so_name in required_sos:
            expected_entry = f"jni/{abi}/{so_name}"
            if expected_entry not in entries:
                raise SystemExit(f"[BUILD_SCRIPT_ERROR] Missing {expected_entry} inside {aar_path}")

aar_sha = hashlib.sha256(aar_path.read_bytes()).hexdigest()
pom_sha = hashlib.sha256(pom_path.read_bytes()).hexdigest()
print(f"[OK] Verified source-built GeckoView AAR: {aar_path} ({aar_path.stat().st_size} bytes, sha256={aar_sha})")
print(f"[OK] Verified source-built GeckoView POM: {pom_path} (sha256={pom_sha})")
PY
