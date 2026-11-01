#!/usr/bin/env bash
# Cài đặt và khóa depot_tools chính thức của Chromium phục vụ quản lý mã nguồn bằng gclient/gn/autoninja.
set -euo pipefail

DEPOT_TOOLS_DIR="${DEPOT_TOOLS_DIR:-${HOME}/depot_tools}"
DEPOT_TOOLS_REPO="https://chromium.googlesource.com/chromium/tools/depot_tools.git"

echo "==> Bootstrapping official Chromium depot_tools into ${DEPOT_TOOLS_DIR}..."
if [[ ! -d "${DEPOT_TOOLS_DIR}/.git" ]]; then
  git clone --depth=1 "${DEPOT_TOOLS_REPO}" "${DEPOT_TOOLS_DIR}"
else
  git -C "${DEPOT_TOOLS_DIR}" fetch origin main --depth=1
  git -C "${DEPOT_TOOLS_DIR}" reset --hard origin/main
fi

export PATH="${DEPOT_TOOLS_DIR}:${PATH}"
if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "${DEPOT_TOOLS_DIR}" >> "${GITHUB_PATH}"
fi

echo "==> Verifying depot_tools binaries..."
command -v gclient
command -v fetch
command -v gn
command -v autoninja
echo "[OK] depot_tools ready at $(git -C "${DEPOT_TOOLS_DIR}" rev-parse --short HEAD)."
