#!/usr/bin/env bash
# Install the MLC Python packages used by CI and verify their native loader path.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MLC_TVM_SHIM_DIR=""
WHEEL_INDEX="${WHEEL_INDEX:-https://mlc.ai/wheels}"
MLC_LLM_PACKAGE="${MLC_LLM_PACKAGE:-mlc-llm-nightly-cpu}"
MLC_AI_PACKAGE="${MLC_AI_PACKAGE:-mlc-ai-nightly-cpu}"

trap 'rm -rf "${MLC_TVM_SHIM_DIR:-}"' EXIT

source "${SCRIPT_DIR}/mlc_python_env.sh"

echo "Installing ${MLC_LLM_PACKAGE} + ${MLC_AI_PACKAGE} from ${WHEEL_INDEX}..."
python3 -m pip install --pre -f "${WHEEL_INDEX}" "${MLC_LLM_PACKAGE}" "${MLC_AI_PACKAGE}"

mlc_add_python_bin_dirs
mlc_configure_native_library_path

echo "Verifying mlc_llm native import..."
if ! mlc_assert_importable; then
  echo "::error::mlc_llm is installed but its native extension could not be loaded." >&2
  python3 -m pip show "${MLC_LLM_PACKAGE%%=*}" || true
  python3 -m pip show "${MLC_AI_PACKAGE%%=*}" || true
  python3 -m pip show apache-tvm-ffi || true
  exit 1
fi
