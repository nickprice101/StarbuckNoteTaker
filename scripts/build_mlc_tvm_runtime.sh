#!/usr/bin/env bash
# =============================================================================
# build_mlc_tvm_runtime.sh
#
# Builds a real Android libtvm4j_runtime_packed.so for the requested ABI.
#
# The bundled Llama model archive uses TVM FFI system-library metadata, so the
# runtime must come from a matching MLC/TVM source generation. The older
# Android-09262024 binary runtime is intentionally not used by default because
# it lacks ffi.SystemLib and fails to load library_bin.
#
# Usage:
#   TARGET_ABI=arm64-v8a bash scripts/build_mlc_tvm_runtime.sh
#   TARGET_ABI=x86_64 bash scripts/build_mlc_tvm_runtime.sh
#
# Prerequisites:
#   - git, rustup, cmake, ninja
#   - Python with mlc_llm importable
#   - Android NDK, with ANDROID_NDK set or discoverable under ANDROID_HOME
#   - app/src/main/assets/Llama-3.2-3B-Instruct-q4f16_0-MLC-android*.tar
#
# Windows note:
#   Run this script from Git Bash. If the MSVC Rust host linker is unavailable,
#   install/use the GNU Rust host toolchain, for example:
#     rustup toolchain install stable-x86_64-pc-windows-gnu
#     rustup target add x86_64-linux-android --toolchain stable-x86_64-pc-windows-gnu
#     RUSTUP_TOOLCHAIN=stable-x86_64-pc-windows-gnu TARGET_ABI=x86_64 bash scripts/fetch_mlc_native.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

source "${SCRIPT_DIR}/mlc_python_env.sh"

MODEL_NAME="Llama-3.2-3B-Instruct-q4f16_0-MLC"
TARGET_ABI="${TARGET_ABI:-arm64-v8a}"
MLC_LLM_REPO_URL="${MLC_LLM_REPO_URL:-https://github.com/mlc-ai/mlc-llm.git}"
MLC_LLM_COMMIT="${MLC_LLM_COMMIT:-d1ea69a87280e821611643958bfec385b62dafd3}"
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

if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/cmake" ]]; then
  sdk_cmake_dir="$(find "${ANDROID_HOME}/cmake" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  if [[ -n "${sdk_cmake_dir}" && -d "${sdk_cmake_dir}/bin" ]]; then
    export PATH="${sdk_cmake_dir}/bin:${PATH}"
  fi
fi

for tool in git cmake ninja rustup; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Required tool not found on PATH: ${tool}" >&2
    exit 1
  fi
done

mlc_configure_compiler_environment

if ! mlc_assert_compiler_importable; then
  echo "mlc_llm is not importable by python3. Install it with:" >&2
  echo "  bash scripts/install_mlc_llm.sh" >&2
  python3 -m pip show mlc-llm 2>/dev/null || python3 -m pip show mlc-llm-nightly-cpu 2>/dev/null || true
  python3 -m pip show mlc-ai-nightly-cpu 2>/dev/null || true
  python3 -m pip show apache-tvm-ffi 2>/dev/null || true
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

python3 "${SCRIPT_DIR}/patch_mlc_prepare_libs.py" \
  --prepare-libs "${PREPARE_LIBS}" \
  --target-abi "${TARGET_ABI}" \
  --rust-target "${RUST_TARGET}"

BUILD_DIR="${MLC4J_DIR}/build"
MODEL_OBJ_DIR="${BUILD_DIR}/model_objs/${TARGET_ABI}"

# prepare_libs.py uses a fixed android/mlc4j/build directory. CI builds arm64
# first and x86_64 second in the same checkout, so stale static archives from
# the first ABI can otherwise be linked into the second ABI's runtime.
rm -rf "${BUILD_DIR}"

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
