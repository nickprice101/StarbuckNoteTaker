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
import tarfile
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

    def patch_file(path: Path, replacements: list[tuple[str, str]]) -> None:
        if not path.is_file():
            return

        text = path.read_text(encoding="utf-8")
        patched = text
        for old, new in replacements:
            patched = patched.replace(old, new)
        if patched != text:
            path.write_text(patched, encoding="utf-8")

    compiler_pass_dir = Path(package_paths[0]) / "compiler_pass"
    patch_file(
        compiler_pass_dir / "attach_sampler.py",
        [
            (
                "@T.prim_func\ndef full",
                "@T.prim_func(check_well_formed=False)\ndef full",
            ),
            (
                "    @T.prim_func\n    def sampler_take_probs_tir",
                "    @T.prim_func(check_well_formed=False)\n    def sampler_take_probs_tir",
            ),
        ],
    )
    patch_file(
        compiler_pass_dir / "attach_softmax_with_temperature.py",
        [
            (
                "    @T.prim_func\n    def chunk_lse",
                "    @T.prim_func(s_tir=True)\n    def chunk_lse",
            ),
            (
                "    @T.prim_func\n    def softmax_with_chunked_sum",
                "    @T.prim_func(s_tir=True)\n    def softmax_with_chunked_sum",
            ),
        ],
    )
    patch_file(
        compiler_pass_dir / "attach_logit_processor.py",
        [
            (
                "    @T.prim_func\n    def _apply_logit_bias_inplace",
                "    @T.prim_func(check_well_formed=False)\n    def _apply_logit_bias_inplace",
            ),
            (
                "    @T.prim_func\n    def _apply_penalty_inplace",
                "    @T.prim_func(check_well_formed=False)\n    def _apply_penalty_inplace",
            ),
            (
                "    @T.prim_func\n    def _apply_bitmask_inplace",
                "    @T.prim_func(check_well_formed=False)\n    def _apply_bitmask_inplace",
            ),
        ],
    )
    patch_file(
        compiler_pass_dir / "attach_spec_decode_aux_funcs.py",
        [
            (
                "    @T.prim_func\n    def _scatter_2d",
                "    @T.prim_func(check_well_formed=False)\n    def _scatter_2d",
            ),
            (
                "    @T.prim_func\n    def _gather_2d",
                "    @T.prim_func(check_well_formed=False)\n    def _gather_2d",
            ),
        ],
    )
    patch_file(
        compiler_pass_dir / "fuse_add_norm.py",
        [
            (
                "    @T.prim_func(private=True)\n    def decode_add_rms",
                "    @T.prim_func(private=True, s_tir=True)\n    def decode_add_rms",
            ),
            (
                "    @T.prim_func(private=True)\n    def prefill_add_rms",
                "    @T.prim_func(private=True, s_tir=True)\n    def prefill_add_rms",
            ),
        ],
    )


def _install_missing_tvm_contrib_stubs() -> None:
    import tvm.contrib as tvm_contrib  # pylint: disable=import-outside-toplevel

    def _module_available(name: str) -> bool:
        try:
            __import__(f"tvm.contrib.{name}")
            return True
        except ImportError:
            return False

    if not _module_available("tar"):
        tar_mod = types.ModuleType("tvm.contrib.tar")

        def create_tar(output, files):
            seen = set()
            with tarfile.open(output, "w") as archive:
                for filename in files:
                    basename = os.path.basename(filename)
                    if basename in seen:
                        raise ValueError(f"duplicate file name {basename}")
                    seen.add(basename)
                    archive.add(filename, arcname=basename)

        create_tar.output_format = "tar"
        tar_mod.tar = create_tar
        tvm_contrib.tar = tar_mod
        sys.modules["tvm.contrib.tar"] = tar_mod

    if not _module_available("ndk"):
        ndk_mod = types.ModuleType("tvm.contrib.ndk")

        def create_shared(*_args, **_kwargs):
            raise RuntimeError("tvm.contrib.ndk is unavailable in this mlc-ai-nightly-cpu wheel")

        ndk_mod.create_shared = create_shared
        tvm_contrib.ndk = ndk_mod
        sys.modules["tvm.contrib.ndk"] = ndk_mod

    if not _module_available("xcode"):
        xcode_mod = types.ModuleType("tvm.contrib.xcode")

        def unavailable(*_args, **_kwargs):
            raise RuntimeError("tvm.contrib.xcode is unavailable in this mlc-ai-nightly-cpu wheel")

        xcode_mod.compile_metal = unavailable
        xcode_mod.create_dylib = unavailable
        tvm_contrib.xcode = xcode_mod
        sys.modules["tvm.contrib.xcode"] = xcode_mod


def _install_missing_tvm_ir_helpers() -> None:
    import tvm.ir  # pylint: disable=import-outside-toplevel
    import tvm_ffi  # pylint: disable=import-outside-toplevel

    if not hasattr(tvm.ir, "structural_equal"):
        tvm.ir.structural_equal = tvm_ffi.structural_equal
    if not hasattr(tvm.ir, "structural_hash"):
        tvm.ir.structural_hash = tvm_ffi.structural_hash


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
    _install_missing_tvm_contrib_stubs()
    _install_missing_tvm_ir_helpers()

    from mlc_llm.cli import compile as compile_cli  # pylint: disable=import-outside-toplevel
    _preserve_explicit_system_lib_prefix(compile_cli)

    if argv[:1] == ["compile"]:
        argv = argv[1:]

    compile_cli.main(argv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
