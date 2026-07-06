#!/usr/bin/env bash
# =============================================================================
# fetch_mlc_native.sh
#
# Downloads libtvm4j_runtime_packed.so from the MLC LLM binary release APK
# and places it in mlc4j/src/main/jniLibs/arm64-v8a/ where the Gradle build
# expects to find it.
#
# Usage (from repository root):
#   bash scripts/fetch_mlc_native.sh
#
# The .so is extracted directly from mlc-chat.apk (which is a ZIP archive)
# without unpacking anything else.  If the file is already present the script
# exits cleanly without re-downloading.
#
# Requirements: curl, unzip (both available on standard Linux/macOS)
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration — update the tag if a newer release is available.
# See https://github.com/mlc-ai/binary-mlc-llm-libs/releases
# ---------------------------------------------------------------------------
RELEASE_TAG="Android-09262024"
TARGET_ABI="${TARGET_ABI:-arm64-v8a}"
APK_URL="https://github.com/mlc-ai/binary-mlc-llm-libs/releases/download/${RELEASE_TAG}/mlc-chat.apk"
SO_INSIDE_APK="lib/arm64-v8a/libtvm4j_runtime_packed.so"
DEST_DIR="mlc4j/src/main/jniLibs/arm64-v8a"
DEST_FILE="${DEST_DIR}/libtvm4j_runtime_packed.so"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ "${TARGET_ABI}" == "x86_64" ]]; then
  echo "The upstream ${RELEASE_TAG} mlc-chat.apk does not contain an x86_64 TVM runtime."
  echo "Building x86_64 from MLC source instead."
  exec bash "${SCRIPT_DIR}/build_mlc_tvm_runtime.sh"
elif [[ "${TARGET_ABI}" != "arm64-v8a" ]]; then
  echo "Unsupported TARGET_ABI: ${TARGET_ABI}" >&2
  echo "Supported values: arm64-v8a, x86_64" >&2
  exit 1
fi

DEST_PATH="${REPO_ROOT}/${DEST_FILE}"

# ---------------------------------------------------------------------------
# Early-exit if already present
# ---------------------------------------------------------------------------
if [[ -f "${DEST_PATH}" ]]; then
  echo "✅  libtvm4j_runtime_packed.so already present at ${DEST_FILE} — nothing to do."
  exit 0
fi

# ---------------------------------------------------------------------------
# Download APK to a temp file
# ---------------------------------------------------------------------------
TMP_APK="$(mktemp /tmp/mlc-chat-XXXXXX.apk)"
trap 'rm -f "${TMP_APK}"' EXIT

echo "⬇️   Downloading mlc-chat.apk (release ${RELEASE_TAG}) …"
echo "     URL: ${APK_URL}"
curl --fail --location --progress-bar --output "${TMP_APK}" "${APK_URL}"
echo "     Downloaded: $(du -sh "${TMP_APK}" | cut -f1)"

# ---------------------------------------------------------------------------
# Extract libtvm4j_runtime_packed.so from the APK (which is a ZIP)
# ---------------------------------------------------------------------------
mkdir -p "${DEST_PATH%/*}"

echo "📦  Extracting ${SO_INSIDE_APK} …"
unzip -p "${TMP_APK}" "${SO_INSIDE_APK}" > "${DEST_PATH}"

if [[ ! -s "${DEST_PATH}" ]]; then
  echo "❌  Extraction failed — ${SO_INSIDE_APK} not found in APK." >&2
  rm -f "${DEST_PATH}"
  exit 1
fi

echo "✅  Saved libtvm4j_runtime_packed.so → ${DEST_FILE} ($(du -sh "${DEST_PATH}" | cut -f1))"
echo ""
echo "Next steps:"
echo "  1. Run TARGET_ABI=${TARGET_ABI} scripts/compile_model_tar.sh to build the real model library .tar"
echo "  2. Run ./gradlew assembleDebug"
