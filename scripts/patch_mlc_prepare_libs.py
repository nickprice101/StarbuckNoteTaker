#!/usr/bin/env python3
"""Patch upstream MLC Android prepare_libs.py for this app's CI build."""

from __future__ import annotations

import argparse
from pathlib import Path


UPSTREAM_LOGGING_IMPORT = (
    "from mlc_llm.support import logging\n\n"
    "logging.enable_logging()\n"
    "logger = logging.getLogger(__name__)\n"
)
STANDARD_LOGGING_IMPORT = (
    "import logging\n\n"
    "logging.basicConfig(level=logging.INFO)\n"
    "logger = logging.getLogger(__name__)\n"
)


def patch_prepare_libs(path: Path, target_abi: str, rust_target: str) -> None:
    text = path.read_text(encoding="utf-8")

    if UPSTREAM_LOGGING_IMPORT in text:
        text = text.replace(UPSTREAM_LOGGING_IMPORT, STANDARD_LOGGING_IMPORT)
    elif "from mlc_llm.support import logging" in text:
        raise RuntimeError("prepare_libs.py uses an unknown mlc_llm logging import pattern")

    text = text.replace("-DANDROID_ABI=arm64-v8a", f"-DANDROID_ABI={target_abi}")
    text = text.replace(
        '"rustup", "target", "add", "aarch64-linux-android"',
        f'"rustup", "target", "add", "{rust_target}"',
    )
    text = text.replace(
        '    android_ndk_path = (\n'
        '        Path(os.environ["ANDROID_NDK"]) / "build" / "cmake" / "android.toolchain.cmake"\n'
        '    )\n',
        '    android_ndk_root = Path(os.environ["ANDROID_NDK"]).resolve()\n'
        '    os.environ["ANDROID_NDK"] = android_ndk_root.as_posix()\n'
        '    os.environ["ANDROID_NDK_HOME"] = android_ndk_root.as_posix()\n'
        '    android_ndk_path = android_ndk_root / "build" / "cmake" / "android.toolchain.cmake"\n',
    )
    text = text.replace(
        '        str(mlc4j_path),\n',
        '        mlc4j_path.resolve().as_posix(),\n',
    )
    text = text.replace(
        '        f"-DCMAKE_TOOLCHAIN_FILE={str(android_ndk_path)}",\n',
        '        f"-DCMAKE_TOOLCHAIN_FILE={android_ndk_path.as_posix()}",\n',
    )

    # The Android emulator exposes Vulkan (including float16/int8 shaders) but
    # not OpenCL. Include TVM's Vulkan runtime for x86_64 so the emulator can
    # execute the 3B kernels through its accelerated graphics stack.
    if target_abi == "x86_64" and '"-DUSE_VULKAN=ON",' not in text:
        opencl_flag = '        "-DUSE_OPENCL=ON",\n'
        if opencl_flag not in text:
            raise RuntimeError("prepare_libs.py has no known USE_OPENCL flag insertion point")
        text = text.replace(
            opencl_flag,
            opencl_flag + '        "-DUSE_VULKAN=ON",\n',
        )

    if "from mlc_llm.support import logging" in text:
        raise RuntimeError("prepare_libs.py still imports mlc_llm.support.logging")
    if f"-DANDROID_ABI={target_abi}" not in text:
        raise RuntimeError(f"prepare_libs.py was not patched for Android ABI {target_abi}")
    if f'"rustup", "target", "add", "{rust_target}"' not in text:
        raise RuntimeError(f"prepare_libs.py was not patched for Rust target {rust_target}")
    if target_abi == "x86_64" and '"-DUSE_VULKAN=ON",' not in text:
        raise RuntimeError("prepare_libs.py was not patched for the Vulkan runtime")

    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-libs", required=True, type=Path)
    parser.add_argument("--target-abi", required=True)
    parser.add_argument("--rust-target", required=True)
    args = parser.parse_args()

    patch_prepare_libs(args.prepare_libs, args.target_abi, args.rust_target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
