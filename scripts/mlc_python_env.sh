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

mlc_prepend_path_var() {
  local var_name="$1"
  local separator="$2"
  local dir
  local current
  local dirs=()
  local index

  shift 2
  dirs=("$@")
  for ((index=${#dirs[@]} - 1; index >= 0; index--)); do
    dir="${dirs[index]}"
    [[ -n "${dir}" && -d "${dir}" ]] || continue
    current="${!var_name:-}"
    case "${separator}${current}${separator}" in
      *"${separator}${dir}${separator}"*) ;;
      *)
        if [[ -n "${current}" ]]; then
          export "${var_name}=${dir}${separator}${current}"
        else
          export "${var_name}=${dir}"
        fi
        ;;
    esac
  done
}

mlc_collect_shared_library_paths() {
  python3 - <<'PYEOF'
import importlib.util
import os
import site
from pathlib import Path

SHARED_SUFFIXES = (".so", ".dylib", ".dll")
SHARED_MARKERS = (".so.",)
MODULES = ("mlc_llm", "tvm", "tvm_ffi")

ordered_dirs = []
seen_dirs = set()


def add_dir(path):
    if not path:
        return
    directory = Path(path).expanduser()
    key = str(directory)
    if key not in seen_dirs:
        seen_dirs.add(key)
        ordered_dirs.append(directory)


def has_shared_library(directory):
    if not directory.is_dir():
        return False
    try:
        for entry in directory.iterdir():
            if not entry.is_file():
                continue
            name = entry.name
            if name.endswith(SHARED_SUFFIXES) or any(marker in name for marker in SHARED_MARKERS):
                return True
    except OSError:
        return False
    return False


for module_name in MODULES:
    try:
        spec = importlib.util.find_spec(module_name)
    except Exception:
        spec = None
    if spec is None:
        continue

    module_dirs = []
    if spec.origin:
        module_dirs.append(Path(spec.origin).parent)
    if spec.submodule_search_locations:
        module_dirs.extend(Path(path) for path in spec.submodule_search_locations)

    for directory in module_dirs:
        add_dir(directory)
        add_dir(directory / "lib")
        add_dir(directory / "bin")

site_dirs = []
try:
    site_dirs.extend(Path(path) for path in site.getsitepackages())
except AttributeError:
    pass
try:
    site_dirs.append(Path(site.getusersitepackages()))
except Exception:
    pass

for site_dir in site_dirs:
    if not site_dir.is_dir():
        continue
    try:
        entries = list(site_dir.iterdir())
    except OSError:
        entries = []

    for entry in entries:
        if not entry.is_dir():
            continue
        name = entry.name
        if (
            name.endswith(".libs")
            or name in MODULES
            or name.startswith(("mlc_", "tvm", "apache_tvm"))
        ):
            add_dir(entry)
            add_dir(entry / "lib")
            add_dir(entry / "bin")

library_dirs = [directory.resolve() for directory in ordered_dirs if has_shared_library(directory)]

tvm_legacy_path = ""
tvm_compiler_path = ""
mlc_tvm_runtime_path = ""
legacy_names = ("libtvm.so", "libtvm.dylib", "tvm.dll")
compiler_names = ("libtvm_compiler.so", "libtvm_compiler.dylib", "tvm_compiler.dll")
runtime_names = ("libtvm_runtime.so", "libtvm_runtime.dylib", "tvm_runtime.dll")
for directory in library_dirs:
    if not tvm_legacy_path:
        for legacy_name in legacy_names:
            legacy = directory / legacy_name
            if legacy.is_file():
                tvm_legacy_path = str(legacy)
                break
    if not tvm_compiler_path:
        for compiler_name in compiler_names:
            compiler = directory / compiler_name
            if compiler.is_file():
                tvm_compiler_path = str(compiler)
                break
    if not mlc_tvm_runtime_path and "mlc_llm" in directory.parts:
        for runtime_name in runtime_names:
            runtime = directory / runtime_name
            if runtime.is_file():
                mlc_tvm_runtime_path = str(runtime)
                break

for directory in library_dirs:
    print(f"DIR\t{directory}")
if tvm_legacy_path:
    print(f"TVM_LEGACY\t{tvm_legacy_path}")
if tvm_compiler_path:
    print(f"TVM_COMPILER\t{tvm_compiler_path}")
if mlc_tvm_runtime_path:
    print(f"MLC_TVM_RUNTIME\t{mlc_tvm_runtime_path}")
PYEOF
}

mlc_create_tvm_compat_alias() {
  local tvm_compat_source_path="$1"
  local alias_dir

  [[ -n "${tvm_compat_source_path}" && -f "${tvm_compat_source_path}" ]] || return 0

  case "$(uname -s 2>/dev/null || echo unknown)" in
    Linux*|GNU*|FreeBSD*) ;;
    *) return 0 ;;
  esac

  alias_dir="${MLC_PYTHON_ENV_LIB_DIR:-${TMPDIR:-/tmp}/mlc-python-env-libs-${UID:-$(id -u 2>/dev/null || echo user)}}"
  mkdir -p "${alias_dir}"

  if ! ln -sfn "${tvm_compat_source_path}" "${alias_dir}/libtvm.so" 2>/dev/null; then
    cp -f "${tvm_compat_source_path}" "${alias_dir}/libtvm.so"
  fi

  printf '%s\n' "${alias_dir}"
}

mlc_configure_shared_libraries() {
  local kind
  local value
  local tvm_legacy_path=""
  local tvm_compiler_path=""
  local mlc_tvm_runtime_path=""
  local alias_dir=""
  local library_dirs=()
  local tvm_compat_source_path=""

  while IFS=$'\t' read -r kind value; do
    case "${kind}" in
      DIR) library_dirs+=("${value}") ;;
      TVM_LEGACY) tvm_legacy_path="${value}" ;;
      TVM_COMPILER) tvm_compiler_path="${value}" ;;
      MLC_TVM_RUNTIME) mlc_tvm_runtime_path="${value}" ;;
    esac
  done < <(mlc_collect_shared_library_paths)

  if [[ -z "${tvm_legacy_path}" && ( -n "${mlc_tvm_runtime_path}" || -n "${tvm_compiler_path}" ) ]]; then
    tvm_compat_source_path="${mlc_tvm_runtime_path:-${tvm_compiler_path}}"
    alias_dir="$(mlc_create_tvm_compat_alias "${tvm_compat_source_path}")"
  fi

  case "$(uname -s 2>/dev/null || echo unknown)" in
    Linux*|GNU*|FreeBSD*)
      mlc_prepend_path_var LD_LIBRARY_PATH ":" "${library_dirs[@]}"
      if [[ -n "${alias_dir}" ]]; then
        mlc_prepend_path_var LD_LIBRARY_PATH ":" "${alias_dir}"
      fi
      ;;
    Darwin*)
      mlc_prepend_path_var DYLD_LIBRARY_PATH ":" "${library_dirs[@]}"
      ;;
  esac
}

mlc_configure_compiler_environment() {
  export SKIP_LOADING_MLCLLM_SO=1
  mlc_add_python_bin_dirs
  mlc_configure_shared_libraries
}

mlc_assert_compiler_importable() {
  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  python3 "${script_dir}/mlc_llm_compile_wrapper.py" --check-import
}
