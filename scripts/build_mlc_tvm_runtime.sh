#!/usr/bin/env bash
# =============================================================================
# build_mlc_tvm_runtime.sh
#
# Builds a real Android libtvm4j_runtime_packed.so for an ABI that is not
# published in the upstream mlc-chat.apk release. This is required for x86_64
# emulator builds because mlc-ai/binary-mlc-llm-libs Android APKs only publish
# arm64-v8a TVM runtimes.
#
# Usage:
#   TARGET_ABI=x86_64 bash scripts/build_mlc_tvm_runtime.sh
#
# Prerequisites:
#   - git, rustup, cmake, ninja
#   - Python with mlc_llm importable
#   - Android NDK, with ANDROID_NDK set or discoverable under ANDROID_HOME
#   - app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android-x86_64.tar
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

MODEL_NAME="Llama-3.2-3B-Instruct-q4f16_0-MLC"
TARGET_ABI="${TARGET_ABI:-x86_64}"
MLC_LLM_REPO_URL="${MLC_LLM_REPO_URL:-https://github.com/mlc-ai/mlc-llm.git}"
MLC_LLM_COMMIT="${MLC_LLM_COMMIT:-32087e8301775f62dcd198540b4862fbac6e79c0}"
MLC_SOURCE_DIR="${MLC_SOURCE_DIR:-${REPO_ROOT}/build/mlc-llm-${MLC_LLM_COMMIT}}"

case "${TARGET_ABI}" in
  arm64-v8a)
    RUST_TARGET="aarch64-linux-android"
    MODEL_TAR_DEFAULT="${REPO_ROOT}/app/src/main/assets/${MODEL_NAME}-android.tar"
    ;;
  x86_64)
    RUST_TARGET="x86_64-linux-android"
    MODEL_TAR_DEFAULT="${REPO_ROOT}/app/src/main/assets/${MODEL_NAME}-android-x86_64.tar"
    ;;
  *)
    echo "Unsupported TARGET_ABI: ${TARGET_ABI}" >&2
    echo "Supported values: arm64-v8a, x86_64" >&2
    exit 1
    ;;
esac

MODEL_TAR="${MODEL_TAR:-${MODEL_TAR_DEFAULT}}"
DEST_DIR="${REPO_ROOT}/mlc4j/src/main/jniLibs/${TARGET_ABI}"
DEST_SO="${DEST_DIR}/libtvm4j_runtime_packed.so"

if [[ -z "${ANDROID_NDK:-}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    latest_ndk_ver="$(ls "${ANDROID_HOME}/ndk" 2>/dev/null | sort -V | tail -1)"
    if [[ -n "${latest_ndk_ver}" ]]; then
      export ANDROID_NDK="${ANDROID_HOME}/ndk/${latest_ndk_ver}"
    fi
  fi
fi

if [[ -z "${ANDROID_NDK:-}" || ! -d "${ANDROID_NDK}" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK=/path/to/android-ndk." >&2
  exit 1
fi

for tool in git cmake rustup; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Required tool not found on PATH: ${tool}" >&2
    exit 1
  fi
done

if [[ "${OS:-}" == "Windows_NT" ]] && ! command -v ninja >/dev/null 2>&1; then
  echo "ninja is required on Windows because MLC's Android prepare script uses Ninja there." >&2
  exit 1
fi

if ! python3 -c "import mlc_llm" >/dev/null 2>&1; then
  echo "mlc_llm is not importable by python3. Install it with:" >&2
  echo "  pip install --pre -f https://mlc.ai/wheels mlc-llm-nightly-cpu mlc-ai-nightly-cpu" >&2
  exit 1
fi

if [[ ! -f "${MODEL_TAR}" ]]; then
  echo "Model tar not found for ${TARGET_ABI}: ${MODEL_TAR}" >&2
  echo "Run TARGET_ABI=${TARGET_ABI} bash scripts/compile_model_tar.sh first." >&2
  exit 1
fi

echo "Building TVM runtime for ${TARGET_ABI}"
echo "  MLC source : ${MLC_SOURCE_DIR}"
echo "  MLC commit : ${MLC_LLM_COMMIT}"
echo "  Model tar  : ${MODEL_TAR}"
echo "  NDK        : ${ANDROID_NDK}"

if [[ ! -d "${MLC_SOURCE_DIR}/.git" ]]; then
  mkdir -p "$(dirname "${MLC_SOURCE_DIR}")"
  git clone --recursive "${MLC_LLM_REPO_URL}" "${MLC_SOURCE_DIR}"
fi

git -C "${MLC_SOURCE_DIR}" fetch --tags --quiet
git -C "${MLC_SOURCE_DIR}" checkout --quiet "${MLC_LLM_COMMIT}"
git -C "${MLC_SOURCE_DIR}" reset --hard --quiet "${MLC_LLM_COMMIT}"
git -C "${MLC_SOURCE_DIR}" submodule update --init --recursive

MLC4J_DIR="${MLC_SOURCE_DIR}/android/mlc4j"
PREPARE_LIBS="${MLC4J_DIR}/prepare_libs.py"

python3 - <<PY
from pathlib import Path
path = Path("${PREPARE_LIBS}")
text = path.read_text(encoding="utf-8")
text = text.replace('-DANDROID_ABI=arm64-v8a', '-DANDROID_ABI=${TARGET_ABI}')
text = text.replace('rustup", "target", "add", "aarch64-linux-android"', 'rustup", "target", "add", "${RUST_TARGET}"')
path.write_text(text, encoding="utf-8")
PY

BUILD_DIR="${MLC4J_DIR}/build"
MODEL_OBJ_DIR="${BUILD_DIR}/model_objs/${TARGET_ABI}"
mkdir -p "${BUILD_DIR}/lib" "${MODEL_OBJ_DIR}"
rm -f "${BUILD_DIR}/lib/libmodel_android.a"
rm -rf "${MODEL_OBJ_DIR:?}/"*
tar -xf "${MODEL_TAR}" -C "${MODEL_OBJ_DIR}"

AR_BIN="$(find "${ANDROID_NDK}/toolchains/llvm/prebuilt" -path "*/bin/llvm-ar*" -type f | head -n 1)"
if [[ -z "${AR_BIN}" ]]; then
  echo "Unable to locate llvm-ar under ${ANDROID_NDK}" >&2
  exit 1
fi

mapfile -t MODEL_OBJECTS < <(find "${MODEL_OBJ_DIR}" -type f -name '*.o' | sort)
if [[ "${#MODEL_OBJECTS[@]}" -eq 0 ]]; then
  echo "No object files found in ${MODEL_TAR}" >&2
  exit 1
fi
"${AR_BIN}" rcs "${BUILD_DIR}/lib/libmodel_android.a" "${MODEL_OBJECTS[@]}"

(
  cd "${MLC4J_DIR}"
  export MLC_LLM_SOURCE_DIR="${MLC_SOURCE_DIR}"
  export TVM_SOURCE_DIR="${MLC_SOURCE_DIR}/3rdparty/tvm"
  python3 prepare_libs.py --mlc-llm-source-dir "${MLC_SOURCE_DIR}"
)

BUILT_SO="${BUILD_DIR}/output/${TARGET_ABI}/libtvm4j_runtime_packed.so"
if [[ ! -s "${BUILT_SO}" ]]; then
  echo "Expected runtime was not produced: ${BUILT_SO}" >&2
  exit 1
fi

mkdir -p "${DEST_DIR}"
cp "${BUILT_SO}" "${DEST_SO}"

if command -v file >/dev/null 2>&1; then
  file "${DEST_SO}"
fi

echo "Saved ${DEST_SO}"
