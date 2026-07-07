#!/usr/bin/env bash
# =============================================================================
# compile_model_tar.sh
#
# Compiles the Llama 3.2 3B Instruct (q4f16_0) model into the MLC system-lib
# format (.tar) and places it at the ABI-specific asset path, for example:
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
#   TARGET_ABI   Android ABI to compile for: arm64-v8a or x86_64.
#                Defaults to arm64-v8a.
#   MLC_DEVICE   MLC target device hint. Defaults to android for arm64-v8a and
#                cpu for x86_64 emulator builds.
#   WEIGHTS_DIR   Path to pre-downloaded quantised weights directory.
#                 Defaults to ./Llama-3.2-3B-Instruct-q4f16_0-MLC (current dir).
#   OUTPUT_TAR    Destination path for the compiled .tar.
#                 Defaults to an ABI-specific file in app/src/main/assets/.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
HF_REPO="mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC"
MODEL_NAME="Llama-3.2-3B-Instruct-q4f16_0-MLC"
TARGET_ABI="${TARGET_ABI:-arm64-v8a}"

case "${TARGET_ABI}" in
  arm64-v8a)
    HOST_TRIPLE="aarch64-linux-android"
    DEFAULT_MLC_DEVICE="android"
    OUTPUT_SUFFIX="android"
    ;;
  x86_64)
    HOST_TRIPLE="x86_64-linux-android"
    DEFAULT_MLC_DEVICE="cpu"
    OUTPUT_SUFFIX="android-x86_64"
    ;;
  *)
    echo "Unsupported TARGET_ABI: ${TARGET_ABI}" >&2
    echo "Supported values: arm64-v8a, x86_64" >&2
    exit 1
    ;;
esac

MLC_DEVICE="${MLC_DEVICE:-${DEFAULT_MLC_DEVICE}}"
WEIGHTS_DIR="${WEIGHTS_DIR:-${REPO_ROOT}/${MODEL_NAME}}"
OUTPUT_TAR="${OUTPUT_TAR:-${REPO_ROOT}/app/src/main/assets/${MODEL_NAME}-${OUTPUT_SUFFIX}.tar}"
COMPILE_OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mlc_compile.XXXXXX")"
MLC_TVM_SHIM_DIR=""

trap 'rm -rf "${COMPILE_OUTPUT_DIR}" "${MLC_TVM_SHIM_DIR:-}"' EXIT

source "${SCRIPT_DIR}/mlc_python_env.sh"

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------
echo "🔍  Checking prerequisites …"

mlc_add_python_bin_dirs

# Determine how to invoke mlc_llm.
#
# Strategy (in order of preference):
#  1. Entry-point script already on PATH.
#  2. Entry-point script in any well-known pip prefix directory.
#  3. Python module invocation – works as long as the package is importable,
#     regardless of whether an entry-point script was installed.
#
# NOTE: We test importability via `python3 -c "import mlc_llm"` rather than
#       `python3 -m mlc_llm --version` because nightly/CPU wheels may not
#       implement the --version flag and would exit non-zero even when the
#       package is perfectly usable.

MLC_LLM_CMD=""

# 1. Check PATH first (covers system-wide installs, e.g. /usr/local/bin).
if command -v mlc_llm &>/dev/null; then
  MLC_LLM_CMD="mlc_llm"
fi

# 2. If not on PATH, search common pip-prefix bin directories.
if [[ -z "${MLC_LLM_CMD}" ]]; then
  _search_dirs=(
    "$(python3 -m site --user-base 2>/dev/null)/bin"   # ~/.local/bin
    "$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null)/bin"  # venv bin
    "/usr/local/bin"
    "/usr/bin"
  )
  for _d in "${_search_dirs[@]}"; do
    if [[ -n "${_d}" && -x "${_d}/mlc_llm" ]]; then
      MLC_LLM_CMD="${_d}/mlc_llm"
      break
    fi
  done
  unset _search_dirs _d
fi

# 3. Fall back to Python module invocation if the package is importable.
if [[ -z "${MLC_LLM_CMD}" ]]; then
  if python3 -c "import mlc_llm" 2>/dev/null; then
    MLC_LLM_CMD="python3 -m mlc_llm"
  elif python -c "import mlc_llm" 2>/dev/null; then
    MLC_LLM_CMD="python -m mlc_llm"
  fi
fi

# 4. If the import check failed (e.g. nightly packages with import-time errors),
#    check if the pip package is actually installed and assume module invocation
#    will work for subcommands (they may lazy-import heavy dependencies).
if [[ -z "${MLC_LLM_CMD}" ]]; then
  if python3 -m pip show mlc-llm-nightly-cpu &>/dev/null || \
     python3 -m pip show mlc-llm &>/dev/null; then
    echo "⚠️  'import mlc_llm' failed but pip package is installed; using module invocation." >&2
    MLC_LLM_CMD="python3 -m mlc_llm"
  elif python -m pip show mlc-llm-nightly-cpu &>/dev/null || \
       python -m pip show mlc-llm &>/dev/null; then
    echo "⚠️  'import mlc_llm' failed but pip package is installed; using module invocation." >&2
    MLC_LLM_CMD="python -m mlc_llm"
  fi
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  echo "❌  mlc_llm not found.  Install it with:" >&2
  echo "      pip install mlc-llm" >&2
  echo "" >&2
  echo "Diagnostic info:" >&2
  echo "  python3  : $(command -v python3 2>/dev/null || echo 'not found')" >&2
  echo "  pip3     : $(command -v pip3 2>/dev/null || echo 'not found')" >&2
  echo "  user-bin : $(python3 -m site --user-base 2>/dev/null)/bin" >&2
  python3 -m pip show mlc-llm 2>/dev/null || python3 -m pip show mlc-llm-nightly-cpu 2>/dev/null || echo "  pip show : package not listed" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Expose libtvm.so via LD_LIBRARY_PATH
# ---------------------------------------------------------------------------
# mlc_llm/base.py loads a TVM shared library via ctypes. Upstream nightly wheels
# may now install that runtime as tvm_ffi/lib/libtvm_ffi.so instead of the
# older site-packages/tvm/libtvm.so layout, so detect both forms and add the
# containing directory (plus a compatibility shim when required) to
# LD_LIBRARY_PATH.
mlc_configure_native_library_path
_MLC_TVM_LIB_PATH=""
: <<'_PYEOF'
import importlib.util, os, site, sys

def _find():
    candidate_names = ("libtvm.so", "libtvm_ffi.so")

    # 1. Check likely TVM/MLC module locations first.
    for mod_name in ("tvm", "tvm_ffi", "mlc_ai", "mlc_llm"):
        try:
            spec = importlib.util.find_spec(mod_name)
            if not spec:
                continue
            search_dirs = []
            if spec.origin:
                search_dirs.append(os.path.dirname(spec.origin))
            if spec.submodule_search_locations:
                search_dirs.extend(spec.submodule_search_locations)
            for directory in search_dirs:
                for relative_dir in ("", "lib"):
                    base_dir = os.path.join(directory, relative_dir)
                    for lib_name in candidate_names:
                        lib_path = os.path.join(base_dir, lib_name)
                        if os.path.isfile(lib_path):
                            return lib_path
        except Exception:
            pass

    # 2. Scan site-packages directories for the legacy flat layout and the
    # newer nested tvm_ffi/lib layout.
    sp_dirs = []
    try:
        sp_dirs += site.getsitepackages()
    except AttributeError:
        pass
    try:
        sp_dirs.append(site.getusersitepackages())
    except Exception:
        pass
    for sp in sp_dirs:
        if not os.path.isdir(sp):
            continue
        try:
            for entry in os.scandir(sp):
                if entry.is_dir(follow_symlinks=True):
                    for relative_dir in ("", "lib"):
                        base_dir = os.path.join(entry.path, relative_dir)
                        for lib_name in candidate_names:
                            lib_path = os.path.join(base_dir, lib_name)
                            if os.path.isfile(lib_path):
                                return lib_path
        except OSError:
            pass
    return ""

print(_find())
_PYEOF

if [[ -n "${_MLC_TVM_LIB_PATH}" ]]; then
  _MLC_TVM_LIB_DIR="$(dirname "${_MLC_TVM_LIB_PATH}")"
  _MLC_TVM_LD_PATH="${_MLC_TVM_LIB_DIR}"
  _MLC_TVM_LIB_BASENAME="$(basename "${_MLC_TVM_LIB_PATH}")"
  _MLC_TVM_NEEDS_COMPATIBILITY_SHIM=0
  if [[ "${_MLC_TVM_LIB_BASENAME}" == "libtvm_ffi.so" ]] && [[ ! -e "${_MLC_TVM_LIB_DIR}/libtvm.so" ]]; then
    _MLC_TVM_NEEDS_COMPATIBILITY_SHIM=1
  fi
  if [[ "${_MLC_TVM_NEEDS_COMPATIBILITY_SHIM}" == "1" ]]; then
    MLC_TVM_SHIM_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mlc_tvm_shim.XXXXXX")"
    ln -s "${_MLC_TVM_LIB_PATH}" "${MLC_TVM_SHIM_DIR}/libtvm.so"
    _MLC_TVM_LD_PATH="${MLC_TVM_SHIM_DIR}:${_MLC_TVM_LD_PATH}"
    echo "     libtvm.so : ${MLC_TVM_SHIM_DIR}/libtvm.so → ${_MLC_TVM_LIB_PATH}"
  else
    echo "     libtvm.so : ${_MLC_TVM_LIB_PATH}"
  fi
  if [[ -n "${LD_LIBRARY_PATH:-}" ]]; then
    export LD_LIBRARY_PATH="${_MLC_TVM_LD_PATH}:${LD_LIBRARY_PATH}"
  else
    export LD_LIBRARY_PATH="${_MLC_TVM_LD_PATH}"
  fi
elif false; then
  echo "     libtvm.so : not found in Python site-packages; ctypes may fail to load it" >&2
fi
unset _MLC_TVM_LIB_PATH _MLC_TVM_LIB_DIR _MLC_TVM_LD_PATH _MLC_TVM_LIB_BASENAME _MLC_TVM_NEEDS_COMPATIBILITY_SHIM

if ! mlc_assert_importable; then
  echo "âŒ  mlc_llm is installed but its native extension could not be loaded." >&2
  echo "" >&2
  echo "Diagnostic info:" >&2
  echo "  python3  : $(command -v python3 2>/dev/null || echo 'not found')" >&2
  echo "  pip3     : $(command -v pip3 2>/dev/null || echo 'not found')" >&2
  echo "  user-bin : $(python3 -m site --user-base 2>/dev/null)/bin" >&2
  python3 -m pip show mlc-llm 2>/dev/null || python3 -m pip show mlc-llm-nightly-cpu 2>/dev/null || echo "  pip show : package not listed" >&2
  python3 -m pip show mlc-ai-nightly-cpu 2>/dev/null || true
  python3 -m pip show apache-tvm-ffi 2>/dev/null || true
  exit 1
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  MLC_LLM_CMD="python3 -m mlc_llm"
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

echo "     mlc_llm : $(${MLC_LLM_CMD} --version 2>&1 | head -1 || echo 'unknown')"
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
# Compile the model library as an Android ABI-specific system-lib .tar
# ---------------------------------------------------------------------------
echo ""
echo "⚙️   Compiling Llama 3.2 3B for Android ${TARGET_ABI} (system-lib) …"
echo "     This may take 10–30 minutes depending on your machine."
echo ""

echo "     Target ABI   : ${TARGET_ABI}"
echo "     MLC device   : ${MLC_DEVICE}"
echo "     Host triple  : ${HOST_TRIPLE}"

${MLC_LLM_CMD} compile \
  "${WEIGHTS_DIR}" \
  --device "${MLC_DEVICE}" \
  --host "${HOST_TRIPLE}" \
  --output "${COMPILE_OUTPUT_DIR}/model.tar"

# ---------------------------------------------------------------------------
# Verify the output contains the expected .o files
# ---------------------------------------------------------------------------
echo ""
echo "🔎  Verifying compiled archive …"

# Dump the archive listing once; use plain 'tar -tf' so that both plain .tar
# and gzip-compressed .tar.gz archives are handled correctly (GNU tar and
# BSD tar both auto-detect compression without an explicit -z flag).
# Normalise entries by stripping any leading "./" so that both "./lib0.o"
# and "lib0.o" style archives are matched uniformly.
TAR_LISTING_ERR="$(mktemp)"
TAR_CONTENTS="$(tar -tf "${COMPILE_OUTPUT_DIR}/model.tar" 2>"${TAR_LISTING_ERR}" | sed 's|^\./||')" || true

if [[ -z "${TAR_CONTENTS}" ]]; then
  echo "❌  Could not list compiled archive." >&2
  if [[ -s "${TAR_LISTING_ERR}" ]]; then
    echo "     tar error: $(cat "${TAR_LISTING_ERR}")" >&2
  fi
  rm -f "${TAR_LISTING_ERR}"
  exit 1
fi
rm -f "${TAR_LISTING_ERR}"

MISSING=()

# lib0.o — TVM runtime support code; always expected.
if ! echo "${TAR_CONTENTS}" | grep -q "^lib0\.o$"; then
  MISSING+=("lib0.o")
fi

# Model code — the exact filenames vary across mlc_llm versions, runtimes, and
# target devices, especially between OpenCL arm64 and CPU x86_64 builds. Accept
# any non-lib0 object file so all model objects produced by MLC are linked.
if ! echo "${TAR_CONTENTS}" | grep -Ev '(^|/)lib0\.o$' | grep -q "\.o$"; then
  MISSING+=("model .o  (no object file other than lib0.o found)")
fi

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "❌  Compiled .tar is missing expected files: ${MISSING[*]}" >&2
  echo "     Archive contents:" >&2
  echo "${TAR_CONTENTS}" | sed 's/^/       /' >&2
  echo "     The output may be from a different quantisation or mlc_llm version." >&2
  exit 1
fi

ARCHIVE_SIZE="$(du -sh "${COMPILE_OUTPUT_DIR}/model.tar" | cut -f1)"
echo "     Archive size : ${ARCHIVE_SIZE}"
echo "     Contents     : $(echo "${TAR_CONTENTS}" | tr '\n' '  ')"

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
echo "  1. Commit the updated .tar:  git add ${OUTPUT_TAR#${REPO_ROOT}/}"
echo "  2. Run TARGET_ABI=${TARGET_ABI} scripts/fetch_mlc_native.sh  (if not done already)"
echo "  3. Run ./gradlew assembleDebug"
