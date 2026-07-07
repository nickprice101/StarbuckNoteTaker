#!/usr/bin/env bash
# Shared helpers for using MLC's Python CLI in CI build scripts.

mlc_add_python_bin_dirs() {
  local user_base
  local sys_prefix

  user_base="$(python3 -m site --user-base 2>/dev/null || true)"
  sys_prefix="$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null || true)"

  [[ -n "${user_base}" ]] && export PATH="${user_base}/bin:${PATH}"
  [[ -n "${sys_prefix}" ]] && export PATH="${sys_prefix}/bin:${PATH}"
}

mlc_configure_compiler_environment() {
  export SKIP_LOADING_MLCLLM_SO=1
  mlc_add_python_bin_dirs
}

mlc_assert_compiler_importable() {
  SKIP_LOADING_MLCLLM_SO=1 python3 - <<'PYEOF'
from mlc_llm.cli import compile as compile_cli
print("     mlc_llm compile module OK:", compile_cli.__file__)
PYEOF
}
