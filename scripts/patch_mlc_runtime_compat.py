#!/usr/bin/env python3
"""Patch upstream MLC runtime source for this app's Android model archives."""

from __future__ import annotations

import argparse
from pathlib import Path


HEADER_FIELD_ANCHOR = "  tvm::ffi::json::Object model_config;\n"
HEADER_FIELD_PATCH = (
    "  tvm::ffi::json::Object model_config;\n"
    "  std::string system_lib_prefix_;\n"
)

INIT_CLEAR_ANCHOR = (
    "  this->model_config = model_config;\n"
    "  this->cached_buffers = Map<String, ObjectRef>();\n"
)
INIT_CLEAR_PATCH = (
    "  this->model_config = model_config;\n"
    "  this->system_lib_prefix_.clear();\n"
    "  this->cached_buffers = Map<String, ObjectRef>();\n"
)

INIT_PREFIX_ANCHOR = (
    "      std::replace(system_lib_prefix.begin(), system_lib_prefix.end(), /*old=*/'-', /*new=*/'_');\n"
    "      executable = f_load_system_lib(system_lib_prefix + \"_\").cast<Module>();\n"
)
INIT_PREFIX_PATCH = (
    "      std::replace(system_lib_prefix.begin(), system_lib_prefix.end(), /*old=*/'-', /*new=*/'_');\n"
    "      this->system_lib_prefix_ = system_lib_prefix;\n"
    "      executable = f_load_system_lib(system_lib_prefix + \"_\").cast<Module>();\n"
)

AUX_LOOKUP_ANCHOR = (
    "  this->softmax_func_ =\n"
    "      mod->GetFunction(\"softmax_with_temperature\", true).value_or(Function(nullptr));\n"
    "  this->apply_logit_bias_func_ =\n"
    "      mod->GetFunction(\"apply_logit_bias_inplace\", true).value_or(Function(nullptr));\n"
    "  this->apply_penalty_func_ =\n"
    "      mod->GetFunction(\"apply_penalty_inplace\", true).value_or(Function(nullptr));\n"
    "  this->apply_bitmask_func_ =\n"
    "      mod->GetFunction(\"apply_bitmask_inplace\", true).value_or(Function(nullptr));\n"
)
AUX_LOOKUP_PATCH = (
    "  const char* kStarbuckRuntimeCompatMarker =\n"
    "      \"starbuck_runtime_compat_prefixed_logit_processor_lookup\";\n"
    "  if (std::getenv(kStarbuckRuntimeCompatMarker) != nullptr) {\n"
    "    LOG(INFO) << kStarbuckRuntimeCompatMarker;\n"
    "  }\n"
    "  auto get_optional_model_func = [this, &mod](const std::string& name) -> Function {\n"
    "    Function func = mod->GetFunction(name, true).value_or(Function(nullptr));\n"
    "    if (func.defined() || this->system_lib_prefix_.empty()) {\n"
    "      return func;\n"
    "    }\n"
    "    func = mod->GetFunction(this->system_lib_prefix_ + name, true).value_or(Function(nullptr));\n"
    "    if (func.defined()) {\n"
    "      return func;\n"
    "    }\n"
    "    return mod->GetFunction(this->system_lib_prefix_ + \"_\" + name, true)\n"
    "        .value_or(Function(nullptr));\n"
    "  };\n"
    "  this->softmax_func_ = get_optional_model_func(\"softmax_with_temperature\");\n"
    "  this->apply_logit_bias_func_ = get_optional_model_func(\"apply_logit_bias_inplace\");\n"
    "  this->apply_penalty_func_ = get_optional_model_func(\"apply_penalty_inplace\");\n"
    "  this->apply_bitmask_func_ = get_optional_model_func(\"apply_bitmask_inplace\");\n"
)


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Could not find expected source block in {path}")
    return text.replace(old, new, 1)


def patch_runtime_source(source_dir: Path) -> None:
    header = source_dir / "cpp" / "serve" / "function_table.h"
    impl = source_dir / "cpp" / "serve" / "function_table.cc"

    header_text = header.read_text(encoding="utf-8")
    header_text = replace_once(header_text, HEADER_FIELD_ANCHOR, HEADER_FIELD_PATCH, header)
    header.write_text(header_text, encoding="utf-8")

    impl_text = impl.read_text(encoding="utf-8")
    impl_text = replace_once(impl_text, INIT_CLEAR_ANCHOR, INIT_CLEAR_PATCH, impl)
    impl_text = replace_once(impl_text, INIT_PREFIX_ANCHOR, INIT_PREFIX_PATCH, impl)
    impl_text = replace_once(impl_text, AUX_LOOKUP_ANCHOR, AUX_LOOKUP_PATCH, impl)
    impl.write_text(impl_text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mlc-source-dir", required=True, type=Path)
    args = parser.parse_args()

    patch_runtime_source(args.mlc_source_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
