#!/usr/bin/env python3
"""Run MLC's compile CLI without importing mlc_llm.__init__.

The pinned CPU wheels used by CI expose the compile modules, but importing the
top-level package also imports serving/runtime objects that require native MLC
registrations. This wrapper bootstraps only the package path needed by
mlc_llm.cli.compile.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import types
from pathlib import Path


def _bootstrap_mlc_package() -> None:
    os.environ["SKIP_LOADING_MLCLLM_SO"] = "1"

    spec = importlib.util.find_spec("mlc_llm")
    if spec is None or spec.submodule_search_locations is None:
        raise RuntimeError("Cannot find installed mlc_llm package")

    package = types.ModuleType("mlc_llm")
    package.__file__ = spec.origin
    package.__path__ = list(spec.submodule_search_locations)
    package.__package__ = "mlc_llm"
    package.__spec__ = spec
    sys.modules["mlc_llm"] = package


def _patch_tirx_well_formed_checks() -> None:
    package = sys.modules.get("mlc_llm")
    package_paths = getattr(package, "__path__", [])
    if not package_paths:
        return

    attach_sampler = Path(package_paths[0]) / "compiler_pass" / "attach_sampler.py"
    if not attach_sampler.is_file():
        return

    text = attach_sampler.read_text(encoding="utf-8")
    patched = text.replace(
        "@T.prim_func\ndef full",
        "@T.prim_func(check_well_formed=False)\ndef full",
    ).replace(
        "    @T.prim_func\n    def sampler_take_probs_tir",
        "    @T.prim_func(check_well_formed=False)\n    def sampler_take_probs_tir",
    )
    if patched != text:
        attach_sampler.write_text(patched, encoding="utf-8")


def _install_missing_tvm_ndk_stub() -> None:
    try:
        import tvm.contrib as tvm_contrib  # pylint: disable=import-outside-toplevel
        import tvm.contrib.ndk  # pylint: disable=unused-import,import-outside-toplevel
    except ImportError:
        import tvm.contrib as tvm_contrib  # pylint: disable=import-outside-toplevel

        ndk = types.ModuleType("tvm.contrib.ndk")

        def create_shared(*_args, **_kwargs):
            raise RuntimeError("tvm.contrib.ndk is unavailable in this mlc-ai-nightly-cpu wheel")

        ndk.create_shared = create_shared
        tvm_contrib.ndk = ndk
        sys.modules["tvm.contrib.ndk"] = ndk


def _check_import_lightweight() -> int:
    """Verify mlc_llm.cli.compile is reachable without triggering TVM native init.

    Newer mlc-ai-nightly-cpu wheels auto-register LLVM targets at import time,
    which fatally crashes on CI runners whose LLVM was not compiled with ARM
    support.  For the pre-flight check we only need to confirm the module file
    exists on disk; the actual import is deferred to compile time.
    """
    import importlib.util as _ilu  # pylint: disable=import-outside-toplevel
    from pathlib import Path  # pylint: disable=import-outside-toplevel

    spec = _ilu.find_spec("mlc_llm")
    if spec is None or spec.submodule_search_locations is None:
        print("     FAIL: mlc_llm package not found", file=sys.stderr)
        return 1

    # Look for mlc_llm/cli/compile.py (or __init__.py inside cli/compile/)
    for search_path in spec.submodule_search_locations:
        cli_compile = Path(search_path) / "cli" / "compile.py"
        cli_compile_pkg = Path(search_path) / "cli" / "compile" / "__init__.py"
        if cli_compile.is_file():
            print("     mlc_llm compile module OK (file check):", cli_compile)
            return 0
        if cli_compile_pkg.is_file():
            print("     mlc_llm compile module OK (file check):", cli_compile_pkg)
            return 0

    # Fallback: attempt the real import (may crash on some CI runners)
    try:
        _bootstrap_mlc_package()
        from mlc_llm.cli import compile as compile_cli  # pylint: disable=import-outside-toplevel
        print("     mlc_llm compile module OK:", compile_cli.__file__)
        return 0
    except (ImportError, RuntimeError, OSError) as exc:
        print(f"     FAIL: mlc_llm.cli.compile import error: {exc}", file=sys.stderr)
        return 1


def _preserve_explicit_system_lib_prefix(compile_cli: types.ModuleType) -> None:
    original = compile_cli.detect_system_lib_prefix

    def detect_system_lib_prefix(
        target_hint: str,
        prefix_hint: str,
        model_name: str,
        quantization: str,
    ) -> str:
        if prefix_hint not in ("", "auto"):
            return prefix_hint
        return original(target_hint, prefix_hint, model_name, quantization)

    compile_cli.detect_system_lib_prefix = detect_system_lib_prefix


def main(argv: list[str]) -> int:
    if argv == ["--check-import"]:
        return _check_import_lightweight()
    if argv == ["--version"]:
        print("mlc_llm compile wrapper")
        return 0

    _bootstrap_mlc_package()
    _patch_tirx_well_formed_checks()
    _install_missing_tvm_ndk_stub()

    from mlc_llm.cli import compile as compile_cli  # pylint: disable=import-outside-toplevel
    _preserve_explicit_system_lib_prefix(compile_cli)

    if argv[:1] == ["compile"]:
        argv = argv[1:]

    compile_cli.main(argv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
