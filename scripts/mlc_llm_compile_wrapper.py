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


def _bootstrap_mlc_package() -> None:
    os.environ["SKIP_LOADING_MLCLLM_SO"] = "1"

    import tvm_ffi.registry as tvm_ffi_registry  # pylint: disable=import-outside-toplevel

    tvm_ffi_registry._SKIP_UNKNOWN_OBJECTS = True  # pylint: disable=protected-access

    spec = importlib.util.find_spec("mlc_llm")
    if spec is None or spec.submodule_search_locations is None:
        raise RuntimeError("Cannot find installed mlc_llm package")

    package = types.ModuleType("mlc_llm")
    package.__file__ = spec.origin
    package.__path__ = list(spec.submodule_search_locations)
    package.__package__ = "mlc_llm"
    package.__spec__ = spec
    sys.modules["mlc_llm"] = package


def main(argv: list[str]) -> int:
    _bootstrap_mlc_package()

    from mlc_llm.cli import compile as compile_cli  # pylint: disable=import-outside-toplevel

    if argv == ["--check-import"]:
        print("     mlc_llm compile module OK:", compile_cli.__file__)
        return 0
    if argv == ["--version"]:
        print("mlc_llm compile wrapper")
        return 0
    if argv[:1] == ["compile"]:
        argv = argv[1:]

    compile_cli.main(argv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
