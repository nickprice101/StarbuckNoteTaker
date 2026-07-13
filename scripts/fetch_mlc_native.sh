#!/usr/bin/env bash
# =============================================================================
# fetch_mlc_native.sh
#
# Ensures libtvm4j_runtime_packed.so is present under mlc4j/src/main/jniLibs/<abi>/.
#
# The checked-in Llama-3.2-3B-Instruct-q4f16_0-MLC model archive is produced by
# a newer TVM/MLC compiler that uses TVM FFI system-library metadata
# (__tvm_ffi_* symbols plus library_bin/library_ctx). The older Android-09262024
# binary APK runtime does not include ffi.SystemLib and cannot load that archive.
# By default this script therefore builds the Android runtime from MLC source.
#
# Usage:
#   bash scripts/fetch_mlc_native.sh
#   TARGET_ABI=x86_64 bash scripts/fetch_mlc_native.sh
#
# Legacy escape hatch:
#   MLC_USE_ANDROID_BINARY_RUNTIME=1 bash scripts/fetch_mlc_native.sh
#
# Requirements for the default path:
#   - git, cmake, ninja, rustup
#   - Python with mlc_llm importable
#   - Android NDK, with ANDROID_NDK set or discoverable under ANDROID_HOME
# =============================================================================

set -euo pipefail

RELEASE_TAG="Android-09262024"
TARGET_ABI="${TARGET_ABI:-arm64-v8a}"
APK_URL="https://github.com/mlc-ai/binary-mlc-llm-libs/releases/download/${RELEASE_TAG}/mlc-chat.apk"
SO_INSIDE_APK="lib/arm64-v8a/libtvm4j_runtime_packed.so"
DEST_DIR="mlc4j/src/main/jniLibs/${TARGET_ABI}"
DEST_FILE="${DEST_DIR}/libtvm4j_runtime_packed.so"
USE_LEGACY_BINARY_RUNTIME="${MLC_USE_ANDROID_BINARY_RUNTIME:-0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_PATH="${REPO_ROOT}/${DEST_FILE}"

runtime_supports_tvm_ffi_system_lib() {
  local runtime_path="$1"
  [[ -f "${runtime_path}" ]] || return 1
  grep -a -q "ffi.SystemLib" "${runtime_path}" &&
    grep -a -q "TVMFFIEnvModRegisterSystemLibSymbol" "${runtime_path}" &&
    grep -a -q "starbuck_runtime_compat_prefixed_logit_processor_lookup" "${runtime_path}"
}

case "${TARGET_ABI}" in
  arm64-v8a|x86_64) ;;
  *)
    echo "Unsupported TARGET_ABI: ${TARGET_ABI}" >&2
    echo "Supported values: arm64-v8a, x86_64" >&2
    exit 1
    ;;
esac

if runtime_supports_tvm_ffi_system_lib "${DEST_PATH}"; then
  echo "Compatible libtvm4j_runtime_packed.so already present at ${DEST_FILE}."
  exit 0
fi

if [[ "${USE_LEGACY_BINARY_RUNTIME}" != "1" ]]; then
  if [[ -f "${DEST_PATH}" ]]; then
    echo "Existing runtime at ${DEST_FILE} lacks TVM FFI system-lib support; rebuilding."
  else
    echo "Runtime missing at ${DEST_FILE}; building from MLC source."
  fi
  export TARGET_ABI
  exec bash "${SCRIPT_DIR}/build_mlc_tvm_runtime.sh"
fi

if [[ "${TARGET_ABI}" != "arm64-v8a" ]]; then
  echo "The ${RELEASE_TAG} mlc-chat.apk only contains an arm64-v8a runtime." >&2
  echo "Unset MLC_USE_ANDROID_BINARY_RUNTIME or run scripts/build_mlc_tvm_runtime.sh." >&2
  exit 1
fi

if [[ -f "${DEST_PATH}" ]]; then
  echo "Existing legacy runtime left in place at ${DEST_FILE}."
  exit 0
fi

TMP_APK="$(mktemp /tmp/mlc-chat-XXXXXX.apk)"
trap 'rm -f "${TMP_APK}"' EXIT

echo "Downloading legacy mlc-chat.apk runtime (release ${RELEASE_TAG})."
echo "This runtime is only for legacy model archives, not the current bundled Llama archive."
echo "URL: ${APK_URL}"
curl --fail --location --progress-bar --output "${TMP_APK}" "${APK_URL}"

mkdir -p "${DEST_PATH%/*}"
echo "Extracting ${SO_INSIDE_APK}."
unzip -p "${TMP_APK}" "${SO_INSIDE_APK}" > "${DEST_PATH}"

if [[ ! -s "${DEST_PATH}" ]]; then
  echo "Extraction failed: ${SO_INSIDE_APK} not found in APK." >&2
  rm -f "${DEST_PATH}"
  exit 1
fi

echo "Saved legacy libtvm4j_runtime_packed.so -> ${DEST_FILE} ($(du -sh "${DEST_PATH}" | cut -f1))"
echo "For the current Llama archive, run:"
echo "  TARGET_ABI=${TARGET_ABI} bash scripts/build_mlc_tvm_runtime.sh"
