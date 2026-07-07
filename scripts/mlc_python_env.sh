#!/usr/bin/env bash
# Shared helpers for installing and loading the MLC Python wheels used by CI.

mlc_add_python_bin_dirs() {
  local user_base
  local sys_prefix

  user_base="$(python3 -m site --user-base 2>/dev/null || true)"
  sys_prefix="$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null || true)"

  [[ -n "${user_base}" ]] && export PATH="${user_base}/bin:${PATH}"
  [[ -n "${sys_prefix}" ]] && export PATH="${sys_prefix}/bin:${PATH}"
}

mlc_locate_python_native_libs() {
  python3 - <<'PYEOF'
import importlib.util
import os
import site


def module_dirs(module_name):
    dirs = []
    try:
        spec = importlib.util.find_spec(module_name)
    except Exception:
        spec = None
    if not spec:
        return dirs
    if spec.origin:
        dirs.append(os.path.dirname(os.path.abspath(spec.origin)))
    if spec.submodule_search_locations:
        dirs.extend(os.path.abspath(path) for path in spec.submodule_search_locations)
    return list(dict.fromkeys(path for path in dirs if path))


def site_package_dirs():
    dirs = []
    try:
        dirs.extend(site.getsitepackages())
    except Exception:
        pass
    try:
        dirs.append(site.getusersitepackages())
    except Exception:
        pass
    return [os.path.abspath(path) for path in dirs if path and os.path.isdir(path)]


def first_existing(candidates):
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return os.path.abspath(candidate)
    return ""


mlc_dirs = module_dirs("mlc_llm")
tvm_dirs = module_dirs("tvm")
tvm_ffi_dirs = module_dirs("tvm_ffi")
site_dirs = site_package_dirs()

mlc_package_dir = mlc_dirs[0] if mlc_dirs else ""
mlc_bundled_runtime = first_existing(
    [os.path.join(path, "lib", "libtvm_runtime.so") for path in mlc_dirs]
    + [os.path.join(path, "libtvm_runtime.so") for path in mlc_dirs]
)

mlc_wheel_libs = ""
if mlc_package_dir:
    candidate = os.path.join(os.path.dirname(mlc_package_dir), "mlc_llm_nightly_cpu.libs")
    if os.path.isdir(candidate):
        mlc_wheel_libs = os.path.abspath(candidate)

mlc_ai_wheel_libs = ""
for base_dir in list(dict.fromkeys(site_dirs + ([os.path.dirname(mlc_package_dir)] if mlc_package_dir else []))):
    candidate = os.path.join(base_dir, "mlc_ai_nightly_cpu.libs")
    if os.path.isdir(candidate):
        mlc_ai_wheel_libs = os.path.abspath(candidate)
        break

tvm_runtime = first_existing(
    [os.path.join(path, "lib", "libtvm_runtime.so") for path in tvm_dirs]
    + [os.path.join(path, "libtvm_runtime.so") for path in tvm_dirs]
)
tvm_compiler = first_existing(
    [os.path.join(path, "lib", "libtvm_compiler.so") for path in tvm_dirs]
    + [os.path.join(path, "libtvm_compiler.so") for path in tvm_dirs]
)
tvm_ffi = first_existing(
    [os.path.join(path, "lib", "libtvm_ffi.so") for path in tvm_ffi_dirs]
    + [os.path.join(path, "libtvm_ffi.so") for path in tvm_ffi_dirs]
)

legacy_libtvm = first_existing(
    [os.path.join(path, "lib", "libtvm.so") for path in tvm_dirs]
    + [os.path.join(path, "libtvm.so") for path in tvm_dirs]
    + [os.path.join(path, "libtvm.so") for path in site_dirs]
)

for key, value in (
    ("mlc_package_dir", mlc_package_dir),
    ("mlc_bundled_runtime", mlc_bundled_runtime),
    ("mlc_wheel_libs", mlc_wheel_libs),
    ("mlc_ai_wheel_libs", mlc_ai_wheel_libs),
    ("tvm_runtime", tvm_runtime),
    ("tvm_compiler", tvm_compiler),
    ("tvm_ffi", tvm_ffi),
    ("legacy_libtvm", legacy_libtvm),
):
    print(f"{key}={value}")
PYEOF
}

mlc_configure_native_library_path() {
  local key
  local value
  local mlc_package_dir=""
  local mlc_bundled_runtime=""
  local mlc_wheel_libs=""
  local mlc_ai_wheel_libs=""
  local tvm_runtime=""
  local tvm_compiler=""
  local tvm_ffi=""
  local legacy_libtvm=""
  local native_info
  local ld_parts=()

  native_info="$(mlc_locate_python_native_libs)"
  while IFS='=' read -r key value; do
    case "${key}" in
      mlc_package_dir) mlc_package_dir="${value}" ;;
      mlc_bundled_runtime) mlc_bundled_runtime="${value}" ;;
      mlc_wheel_libs) mlc_wheel_libs="${value}" ;;
      mlc_ai_wheel_libs) mlc_ai_wheel_libs="${value}" ;;
      tvm_runtime) tvm_runtime="${value}" ;;
      tvm_compiler) tvm_compiler="${value}" ;;
      tvm_ffi) tvm_ffi="${value}" ;;
      legacy_libtvm) legacy_libtvm="${value}" ;;
    esac
  done <<< "${native_info}"

  if [[ -n "${mlc_bundled_runtime}" ]]; then
    MLC_TVM_SHIM_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mlc_tvm_shim.XXXXXX")"
    if ! ln -s "${mlc_bundled_runtime}" "${MLC_TVM_SHIM_DIR}/libtvm.so" 2>/dev/null; then
      cp "${mlc_bundled_runtime}" "${MLC_TVM_SHIM_DIR}/libtvm.so"
    fi
    echo "     libtvm.so : ${MLC_TVM_SHIM_DIR}/libtvm.so -> ${mlc_bundled_runtime}"
    ld_parts+=("${MLC_TVM_SHIM_DIR}" "$(dirname "${mlc_bundled_runtime}")")
  elif [[ -n "${legacy_libtvm}" ]]; then
    echo "     libtvm.so : ${legacy_libtvm}"
    ld_parts+=("$(dirname "${legacy_libtvm}")")
  else
    echo "     libtvm.so : not found; mlc_llm native import may fail" >&2
  fi

  [[ -n "${mlc_package_dir}" ]] && ld_parts+=("${mlc_package_dir}")
  [[ -n "${mlc_wheel_libs}" ]] && ld_parts+=("${mlc_wheel_libs}")
  [[ -n "${mlc_ai_wheel_libs}" ]] && ld_parts+=("${mlc_ai_wheel_libs}")
  [[ -n "${tvm_ffi}" ]] && ld_parts+=("$(dirname "${tvm_ffi}")")
  [[ -n "${tvm_runtime}" ]] && ld_parts+=("$(dirname "${tvm_runtime}")")
  [[ -n "${tvm_compiler}" ]] && ld_parts+=("$(dirname "${tvm_compiler}")")

  local joined=""
  local part
  for part in "${ld_parts[@]}"; do
    [[ -z "${part}" || ! -d "${part}" ]] && continue
    case ":${joined}:" in
      *":${part}:"*) ;;
      *) joined="${joined:+${joined}:}${part}" ;;
    esac
  done

  if [[ -n "${joined}" ]]; then
    export LD_LIBRARY_PATH="${joined}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
  fi
}

mlc_assert_importable() {
  python3 - <<'PYEOF'
import mlc_llm
print("     mlc_llm module OK:", mlc_llm.__file__)
PYEOF
}
