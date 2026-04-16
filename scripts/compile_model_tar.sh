#!/usr/bin/env bash
# =============================================================================
# compile_model_tar.sh
#
# Compiles the Llama 3.2 3B Instruct (q4f16_0) model into the MLC system-lib
# format (.tar) and places it at:
#
#   app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
#
# The .tar is bundled inside the APK and extracted at runtime by
# LlamaModelManager.extractModelLibIfNeeded().  It contains:
#   lib0.o                   — TVM runtime support code
#   llama_q4f16_0_devc.o     — quantised Llama device code
#
# Usage (from repository root):
#   bash scripts/compile_model_tar.sh
#
# Requirements:
#   - Python 3.10+
#   - mlc_llm Python package  (pip install mlc-llm)
#   - Android NDK r27+        (set ANDROID_NDK env var or have it in PATH)
#   - ~20 GB free disk space  (weights + compilation intermediates)
#
# Optional env vars:
#   WEIGHTS_DIR   Path to pre-downloaded quantised weights directory.
#                 Defaults to ./Llama-3.2-3B-Instruct-q4f16_0-MLC (current dir).
#   OUTPUT_TAR    Destination path for the compiled .tar.
#                 Defaults to app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android.tar
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
HF_REPO="mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC"
MODEL_NAME="Llama-3.2-3B-Instruct-q4f16_0-MLC"
WEIGHTS_DIR="${WEIGHTS_DIR:-${REPO_ROOT}/${MODEL_NAME}}"
OUTPUT_TAR="${OUTPUT_TAR:-${REPO_ROOT}/app/src/main/assets/${MODEL_NAME}-android.tar}"
COMPILE_OUTPUT_DIR="$(mktemp -d /tmp/mlc_compile_XXXXXX)"

trap 'rm -rf "${COMPILE_OUTPUT_DIR}"' EXIT

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------
echo "🔍  Checking prerequisites …"

if ! command -v mlc_llm &>/dev/null; then
  echo "❌  mlc_llm not found.  Install it with:" >&2
  echo "      pip install mlc-llm" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK:-}" ]]; then
  # Try common locations, guarding against unset ANDROID_HOME
  ndk_candidates=()
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    latest_ndk_ver="$(ls "${ANDROID_HOME}/ndk" 2>/dev/null | sort -V | tail -1)"
    [[ -n "${latest_ndk_ver}" ]] && ndk_candidates+=("${ANDROID_HOME}/ndk/${latest_ndk_ver}")
  fi
  ndk_candidates+=("${NDK_HOME:-}" "/usr/local/android-ndk" "/opt/android-ndk")
  for candidate in "${ndk_candidates[@]}"; do
    if [[ -n "${candidate}" && -d "${candidate}" ]]; then
      export ANDROID_NDK="${candidate}"
      break
    fi
  done
fi

if [[ -z "${ANDROID_NDK:-}" || ! -d "${ANDROID_NDK}" ]]; then
  echo "❌  Android NDK not found.  Set the ANDROID_NDK environment variable:" >&2
  echo "      export ANDROID_NDK=/path/to/android-ndk-r27" >&2
  exit 1
fi

echo "     mlc_llm : $(mlc_llm --version 2>&1 | head -1 || echo 'unknown')"
echo "     NDK     : ${ANDROID_NDK}"

# ---------------------------------------------------------------------------
# Download quantised weights if not already present
# ---------------------------------------------------------------------------
if [[ ! -d "${WEIGHTS_DIR}" ]]; then
  echo ""
  echo "⬇️   Quantised weights not found at ${WEIGHTS_DIR}."
  echo "     Downloading from HuggingFace (${HF_REPO}) …"

  if command -v git &>/dev/null && git lfs version &>/dev/null 2>&1; then
    GIT_LFS_SKIP_SMUDGE=1 git clone \
      "https://huggingface.co/${HF_REPO}" \
      "${WEIGHTS_DIR}" \
      --depth 1
    pushd "${WEIGHTS_DIR}" > /dev/null
    git lfs pull
    popd > /dev/null
  else
    echo "❌  git-lfs is required to download the weights." >&2
    echo "     Install it with: git lfs install  (https://git-lfs.com)" >&2
    exit 1
  fi
else
  echo "✅  Using existing weights at ${WEIGHTS_DIR}"
fi

# ---------------------------------------------------------------------------
# Compile the model library as an Android arm64 system-lib .tar
# ---------------------------------------------------------------------------
echo ""
echo "⚙️   Compiling Llama 3.2 3B for Android arm64 (system-lib) …"
echo "     This may take 10–30 minutes depending on your machine."
echo ""

mlc_llm compile \
  "${WEIGHTS_DIR}" \
  --target android \
  --device "android:arm64-v8a" \
  --system-lib \
  --output "${COMPILE_OUTPUT_DIR}/model.tar"

# ---------------------------------------------------------------------------
# Verify the output contains the expected .o files
# ---------------------------------------------------------------------------
echo ""
echo "🔎  Verifying compiled archive …"

REQUIRED_FILES=("lib0.o" "llama_q4f16_0_devc.o")
MISSING=()
for f in "${REQUIRED_FILES[@]}"; do
  if ! tar -tzf "${COMPILE_OUTPUT_DIR}/model.tar" 2>/dev/null | grep -q "^${f}$"; then
    MISSING+=("${f}")
  fi
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "❌  Compiled .tar is missing expected files: ${MISSING[*]}" >&2
  echo "     The output may be from a different quantisation or mlc_llm version." >&2
  exit 1
fi

ARCHIVE_SIZE="$(du -sh "${COMPILE_OUTPUT_DIR}/model.tar" | cut -f1)"
echo "     Archive size : ${ARCHIVE_SIZE}"
echo "     Contents     : $(tar -tzf "${COMPILE_OUTPUT_DIR}/model.tar" | tr '\n' '  ')"

# ---------------------------------------------------------------------------
# Copy to assets
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "${OUTPUT_TAR}")"
cp "${COMPILE_OUTPUT_DIR}/model.tar" "${OUTPUT_TAR}"

echo ""
echo "✅  Compiled model library written to:"
echo "     ${OUTPUT_TAR}"
echo ""
echo "Next steps:"
echo "  1. Commit the updated .tar:  git add app/src/main/assets/${MODEL_NAME}-android.tar"
echo "  2. Run scripts/fetch_mlc_native.sh  (if not done already)"
echo "  3. Run ./gradlew assembleDebug"
