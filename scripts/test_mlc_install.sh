#!/usr/bin/env bash
# =============================================================================
# test_mlc_install.sh
#
# Validates that mlc-llm can be installed from the mlc.ai nightly wheel index
# and that the detection logic used by compile_model_tar.sh is able to locate
# the mlc_llm entry-point or Python module.
#
# Exit codes:
#   0  – all checks passed
#   1  – one or more checks failed
#
# Usage (from repository root):
#   bash scripts/test_mlc_install.sh
#
# Optional env vars:
#   SKIP_INSTALL=1   Skip the pip install step (assume packages already present).
#   WHEEL_INDEX      Custom wheel index URL (default: https://mlc.ai/wheels).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHEEL_INDEX="${WHEEL_INDEX:-https://mlc.ai/wheels}"
MLC_LLM_PACKAGE="${MLC_LLM_PACKAGE:-mlc-llm-nightly-cpu}"
MLC_AI_PACKAGE="${MLC_AI_PACKAGE:-mlc-ai-nightly-cpu}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"

source "${SCRIPT_DIR}/mlc_python_env.sh"

PASS=0
FAIL=0

pass() { echo "  ✅  $*"; (( PASS++ )) || true; }
fail() { echo "  ❌  $*" >&2; (( FAIL++ )) || true; }

echo "============================================================"
echo " mlc-llm install & detection test"
echo "============================================================"

# ---------------------------------------------------------------------------
# 1. Install (unless the caller already has it installed)
# ---------------------------------------------------------------------------
if [[ "${SKIP_INSTALL}" != "1" ]]; then
  echo ""
  echo "📦  Installing ${MLC_LLM_PACKAGE} + ${MLC_AI_PACKAGE} …"
  pip install --pre -f "${WHEEL_INDEX}" "${MLC_LLM_PACKAGE}" "${MLC_AI_PACKAGE}"
  echo ""
fi

# Add user-base and sys.prefix bin dirs so we catch entry-points wherever pip
# decided to put them.
mlc_configure_compiler_environment

# ---------------------------------------------------------------------------
# 2. Package listing
# ---------------------------------------------------------------------------
echo "🔍  Checking installed package metadata …"
if python3 -m pip show "${MLC_LLM_PACKAGE%%=*}" &>/dev/null; then
  pass "pip show ${MLC_LLM_PACKAGE%%=*}"
  python3 -m pip show "${MLC_LLM_PACKAGE%%=*}" | sed 's/^/     /'
elif python3 -m pip show mlc-llm &>/dev/null; then
  pass "pip show mlc-llm (stable)"
else
  fail "mlc-llm package not found by pip show"
fi

# ---------------------------------------------------------------------------
# 3. Python importability – the definitive check
# ---------------------------------------------------------------------------
echo ""
echo "🔍  Checking Python importability …"
if mlc_assert_compiler_importable 2>/dev/null; then
  pass "mlc_llm compile module import"
else
  fail "mlc_llm compile module import failed"
  mlc_assert_compiler_importable 2>&1 | sed 's/^/     /' || true
fi

# ---------------------------------------------------------------------------
# 4. Simulate the full discovery logic from compile_model_tar.sh
# ---------------------------------------------------------------------------
echo ""
echo "🔍  Simulating compile_model_tar.sh mlc_llm discovery logic …"

MLC_LLM_CMD=""

if command -v mlc_llm &>/dev/null; then
  MLC_LLM_CMD="mlc_llm"
  pass "command -v mlc_llm → $(command -v mlc_llm)"
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  _search_dirs=(
    "$(python3 -m site --user-base 2>/dev/null)/bin"
    "$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null)/bin"
    "/usr/local/bin"
    "/usr/bin"
  )
  for _d in "${_search_dirs[@]}"; do
    if [[ -n "${_d}" && -x "${_d}/mlc_llm" ]]; then
      MLC_LLM_CMD="${_d}/mlc_llm"
      pass "found entry-point at ${_d}/mlc_llm"
      break
    fi
  done
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  if mlc_assert_compiler_importable &>/dev/null; then
    MLC_LLM_CMD="python3 ${SCRIPT_DIR}/mlc_llm_compile_wrapper.py"
    pass "compile wrapper import check -> will use: ${MLC_LLM_CMD}"
  fi
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  fail "compile_model_tar.sh detection logic would FAIL – mlc_llm not reachable"
else
  pass "mlc_llm would be invoked as: ${MLC_LLM_CMD}"
fi

# ---------------------------------------------------------------------------
# 5. Regression test: locate the renamed TVM FFI runtime layout
# ---------------------------------------------------------------------------
echo ""
echo "🔍  Verifying TVM shared-library discovery handles tvm_ffi/lib/libtvm_ffi.so …"

FAKE_USER_BASE="$(mktemp -d "${TMPDIR:-/tmp}/mlc_userbase.XXXXXX")"
PYTHON_MAJOR_MINOR="$(python3 - <<'PY'
import sys
print(f"{sys.version_info.major}.{sys.version_info.minor}")
PY
)"
EXPECTED_TVM_LIB_PATH="${FAKE_USER_BASE}/lib/python${PYTHON_MAJOR_MINOR}/site-packages/tvm_ffi/lib/libtvm_ffi.so"
EXPECTED_TVM_COMPILER_PATH="${FAKE_USER_BASE}/lib/python${PYTHON_MAJOR_MINOR}/site-packages/tvm/lib/libtvm_compiler.so"
EXPECTED_MLC_LIB_PATH="${FAKE_USER_BASE}/lib/python${PYTHON_MAJOR_MINOR}/site-packages/mlc_llm/lib/libmlc_llm_module.so"
EXPECTED_MLC_TVM_RUNTIME_PATH="${FAKE_USER_BASE}/lib/python${PYTHON_MAJOR_MINOR}/site-packages/mlc_llm/lib/libtvm_runtime.so"
EXPECTED_ALIAS_DIR="${FAKE_USER_BASE}/mlc-alias"
mkdir -p "$(dirname "${EXPECTED_TVM_LIB_PATH}")"
mkdir -p "$(dirname "${EXPECTED_TVM_COMPILER_PATH}")"
mkdir -p "$(dirname "${EXPECTED_MLC_LIB_PATH}")"
touch "${EXPECTED_TVM_LIB_PATH}"
touch "${EXPECTED_TVM_COMPILER_PATH}"
touch "${EXPECTED_MLC_LIB_PATH}"
touch "${EXPECTED_MLC_TVM_RUNTIME_PATH}"

if (
  export PYTHONUSERBASE="${FAKE_USER_BASE}"
  export MLC_PYTHON_ENV_LIB_DIR="${EXPECTED_ALIAS_DIR}"
  unset LD_LIBRARY_PATH
  mlc_configure_compiler_environment
  [[ ":${LD_LIBRARY_PATH:-}:" == *":$(dirname "${EXPECTED_TVM_LIB_PATH}"):"* ]] &&
  [[ ":${LD_LIBRARY_PATH:-}:" == *":$(dirname "${EXPECTED_TVM_COMPILER_PATH}"):"* ]] &&
  [[ -e "${EXPECTED_ALIAS_DIR}/libtvm.so" ]]
); then
  pass "native library path includes tvm_ffi/tvm dirs and libtvm.so alias"
else
  fail "native library path setup did not expose the expected TVM libraries"
  echo "     expected tvm_ffi dir : $(dirname "${EXPECTED_TVM_LIB_PATH}")" >&2
  echo "     expected tvm dir     : $(dirname "${EXPECTED_TVM_COMPILER_PATH}")" >&2
  echo "     expected alias       : ${EXPECTED_ALIAS_DIR}/libtvm.so" >&2
fi

rm -rf "${FAKE_USER_BASE}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "============================================================"
echo " Results: ${PASS} passed, ${FAIL} failed"
echo "============================================================"

if [[ ${FAIL} -gt 0 ]]; then
  echo ""
  echo "Diagnostic PATH    : ${PATH}"
  echo "python3 executable : $(command -v python3)"
  echo "sys.path           :"
  python3 -c "import sys; [print('  ', p) for p in sys.path]"
  exit 1
fi
