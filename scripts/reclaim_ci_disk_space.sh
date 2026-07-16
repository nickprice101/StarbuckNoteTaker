#!/usr/bin/env bash
# Reclaim GitHub-hosted runner disk space without removing generated Android
# artifacts that are still needed by the APK build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODEL_NAME="Llama-3.2-3B-Instruct-q4f16_0-MLC"
MODEL_NAME_X86_64="Llama-3.2-1B-Instruct-q4f32_1-MLC"
PHASE=""
DRY_RUN="${DRY_RUN:-0}"

usage() {
  cat >&2 <<'EOF'
Usage:
  bash scripts/reclaim_ci_disk_space.sh [--dry-run] (--before-mlc|--before-gradle)

Options:
  --dry-run        Prints what would be removed without deleting files.
  --before-mlc     Removes large hosted-runner toolchains that this Android build
                 does not use.
  --before-gradle  Removes MLC generation leftovers after required model/runtime
                 artifacts have been produced.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    --before-mlc|--before-gradle)
      PHASE="$1"
      ;;
    *)
      usage
      exit 2
      ;;
  esac
  shift
done

show_disk() {
  local label="$1"
  echo "Disk space ${label}:"
  df -h /
}

remove_path() {
  local path="$1"
  if [[ ! -e "${path}" && ! -L "${path}" ]]; then
    return 0
  fi

  if [[ "${DRY_RUN}" == "1" ]]; then
    echo "[dry-run] Would remove ${path}"
  else
    echo "Removing ${path}"
    rm -rf -- "${path}"
  fi
}

sudo_remove_path() {
  local path="$1"
  if [[ ! -e "${path}" && ! -L "${path}" ]]; then
    return 0
  fi

  if [[ "${DRY_RUN}" == "1" ]]; then
    echo "[dry-run] Would remove ${path}"
  elif command -v sudo >/dev/null 2>&1; then
    echo "Removing ${path}"
    sudo rm -rf -- "${path}"
  else
    echo "Skipping ${path}; sudo is not available."
  fi
}

remove_matching_dirs() {
  local parent="$1"
  shift

  [[ -d "${parent}" ]] || return 0

  local match
  while IFS= read -r -d '' match; do
    remove_path "${match}"
  done < <(find "${parent}" -mindepth 1 -maxdepth 1 -type d "$@" -print0)
}

require_file() {
  local path="$1"
  if [[ ! -s "${path}" ]]; then
    echo "::error::Required build artifact is missing or empty: ${path#${REPO_ROOT}/}" >&2
    return 1
  fi
}

verify_required_mlc_artifacts() {
  local missing=0

  require_file "${REPO_ROOT}/app/src/main/assets/${MODEL_NAME}-android.tar" || missing=1
  require_file "${REPO_ROOT}/app/src/main/assets/${MODEL_NAME_X86_64}-android-x86_64.tar" || missing=1
  require_file "${REPO_ROOT}/mlc4j/src/main/jniLibs/arm64-v8a/libtvm4j_runtime_packed.so" || missing=1
  require_file "${REPO_ROOT}/mlc4j/src/main/jniLibs/x86_64/libtvm4j_runtime_packed.so" || missing=1

  if [[ "${missing}" == "1" ]]; then
    echo "::error::Refusing to clean MLC build directories until all packaged ABI artifacts exist." >&2
    exit 1
  fi
}

purge_python_caches() {
  if [[ "${DRY_RUN}" == "1" ]]; then
    echo "[dry-run] Would purge the Python pip cache"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m pip cache purge || true
  fi

  remove_path "${HOME}/.cache/pip"
  remove_path "${HOME}/.cache/huggingface"
  remove_path "${HOME}/.cache/mlc_llm"
  remove_path "${HOME}/.cache/tvm"
}

case "${PHASE}" in
  --before-mlc)
    show_disk "before hosted-tool cleanup"
    sudo_remove_path "/usr/share/dotnet"
    sudo_remove_path "/opt/ghc"
    sudo_remove_path "/usr/local/share/boost"
    sudo_remove_path "/usr/local/share/powershell"
    show_disk "after hosted-tool cleanup"
    ;;

  --before-gradle)
    verify_required_mlc_artifacts
    show_disk "before MLC cleanup"

    remove_path "${REPO_ROOT}/${MODEL_NAME}"
    remove_matching_dirs "${REPO_ROOT}/build" \
      \( -name "mlc-llm-*" -o -name "mlc_venv" -o -name "mlc_download" \)
    remove_matching_dirs "${TMPDIR:-/tmp}" -name "mlc_compile.*"
    purge_python_caches

    if [[ -n "${ANDROID_HOME:-}" && "${KEEP_ANDROID_NDK_27:-0}" != "1" ]]; then
      remove_path "${ANDROID_HOME}/ndk/27.2.12479018"
    fi

    show_disk "after MLC cleanup"
    ;;

  *)
    usage
    exit 2
    ;;
esac
