#!/usr/bin/env bash
# Install the MLC Python packages used by CI and verify the compile CLI import.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHEEL_INDEX="${WHEEL_INDEX:-https://mlc.ai/wheels}"
MLC_LLM_PACKAGE="${MLC_LLM_PACKAGE:-mlc-llm-nightly-cpu}"
MLC_AI_PACKAGE="${MLC_AI_PACKAGE:-mlc-ai-nightly-cpu}"
MLC_FFI_PACKAGE="${MLC_FFI_PACKAGE:-apache-tvm-ffi==0.1.11}"
MLC_EXTRA_PACKAGES="${MLC_EXTRA_PACKAGES:-pytest}"

source "${SCRIPT_DIR}/mlc_python_env.sh"

echo "Installing ${MLC_LLM_PACKAGE} + ${MLC_AI_PACKAGE} + ${MLC_FFI_PACKAGE} from ${WHEEL_INDEX}..."
python3 -m pip install --pre -f "${WHEEL_INDEX}" \
  "${MLC_FFI_PACKAGE}" \
  "${MLC_LLM_PACKAGE}" \
  "${MLC_AI_PACKAGE}"

if [[ -n "${MLC_EXTRA_PACKAGES}" ]]; then
  echo "Installing extra MLC build packages: ${MLC_EXTRA_PACKAGES}"
  # shellcheck disable=SC2086 # Intentional word splitting for package lists.
  python3 -m pip install ${MLC_EXTRA_PACKAGES}
fi

mlc_configure_compiler_environment

echo "Verifying mlc_llm compile CLI import..."
if ! mlc_assert_compiler_importable; then
  echo "::error::mlc_llm is installed but its compile CLI could not be imported." >&2
  python3 -m pip show "${MLC_LLM_PACKAGE%%=*}" || true
  python3 -m pip show "${MLC_AI_PACKAGE%%=*}" || true
  python3 -m pip show "${MLC_FFI_PACKAGE%%=*}" || true
  exit 1
fi
