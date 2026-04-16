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

WHEEL_INDEX="${WHEEL_INDEX:-https://mlc.ai/wheels}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"

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
  echo "📦  Installing mlc-llm-nightly-cpu + mlc-ai-nightly-cpu …"
  pip install --pre -f "${WHEEL_INDEX}" mlc-llm-nightly-cpu mlc-ai-nightly-cpu
  echo ""
fi

# Add user-base and sys.prefix bin dirs so we catch entry-points wherever pip
# decided to put them.
export PATH="$(python3 -m site --user-base 2>/dev/null)/bin:${PATH}"
export PATH="$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null)/bin:${PATH}"

# ---------------------------------------------------------------------------
# 2. Package listing
# ---------------------------------------------------------------------------
echo "🔍  Checking installed package metadata …"
if python3 -m pip show mlc-llm-nightly-cpu &>/dev/null; then
  pass "pip show mlc-llm-nightly-cpu"
  python3 -m pip show mlc-llm-nightly-cpu | sed 's/^/     /'
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
if python3 -c "import mlc_llm; print('     mlc_llm.__file__:', mlc_llm.__file__)" 2>/dev/null; then
  pass "python3 -c 'import mlc_llm'"
else
  fail "python3 -c 'import mlc_llm' failed"
  python3 -c "import mlc_llm" 2>&1 | sed 's/^/     /' || true
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
  if python3 -c "import mlc_llm" &>/dev/null; then
    MLC_LLM_CMD="python3 -m mlc_llm"
    pass "python3 -c 'import mlc_llm' → will use: python3 -m mlc_llm"
  elif python -c "import mlc_llm" &>/dev/null; then
    MLC_LLM_CMD="python -m mlc_llm"
    pass "python -c 'import mlc_llm' → will use: python -m mlc_llm"
  fi
fi

if [[ -z "${MLC_LLM_CMD}" ]]; then
  fail "compile_model_tar.sh detection logic would FAIL – mlc_llm not reachable"
else
  pass "mlc_llm would be invoked as: ${MLC_LLM_CMD}"
fi

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
