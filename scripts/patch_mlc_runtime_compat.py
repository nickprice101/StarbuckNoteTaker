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

ANDROID_VULKAN_ANCHOR = (
    "    set(Vulkan_FOUND TRUE)\n"
    "    message(STATUS \"Android Vulkan_INCLUDE_DIRS=\" ${Vulkan_INCLUDE_DIRS})\n"
    "    message(STATUS \"Skip finding SPIRV in Android, make sure you only build tvm runtime.\")\n"
    "    return()\n"
)
ANDROID_VULKAN_LEGACY_PATCH = (
    "    set(Vulkan_FOUND TRUE)\n"
    "    # Link Android's platform Vulkan loader. Do not return from this macro:\n"
    "    # return() exits Vulkan.cmake before it registers the runtime sources.\n"
    "    set(Vulkan_LIBRARY vulkan)\n"
    "    message(STATUS \"Android Vulkan_INCLUDE_DIRS=\" ${Vulkan_INCLUDE_DIRS})\n"
)
ANDROID_VULKAN_PATCH = (
    "    set(Vulkan_FOUND TRUE)\n"
    "    # Link Android's platform Vulkan loader. Do not return from this macro:\n"
    "    # return() exits Vulkan.cmake before it registers the runtime sources.\n"
    "    set(Vulkan_LIBRARY vulkan)\n"
    "    # Runtime-only Android builds do not need host SPIR-V tools. Seed the\n"
    "    # generic discovery variables so CMake does not append *-NOTFOUND paths.\n"
    "    set(Vulkan_SPIRV_TOOLS_LIBRARY vulkan)\n"
    "    set(_libspirv ${Vulkan_INCLUDE_DIRS})\n"
    "    set(_spirv ${Vulkan_INCLUDE_DIRS})\n"
    "    set(_glsl_std ${Vulkan_INCLUDE_DIRS})\n"
    "    message(STATUS \"Android Vulkan_INCLUDE_DIRS=\" ${Vulkan_INCLUDE_DIRS})\n"
)

ANDROID_WHOLE_RUNTIME_LEGACY_PATCH = (
    "  mlc_llm_static\n"
    "  tvm_runtime\n"
    "  model_android\n"
)
ANDROID_WHOLE_RUNTIME_ANCHOR = (
    "  mlc_llm_static\n"
    "  model_android\n"
)

ANDROID_VULKAN_SOURCES_ANCHOR = (
    "add_library(\n"
    "  tvm4j_runtime_packed SHARED\n"
    "  ${TVM_SOURCE_DIR}/jvm/native/src/main/native/org_apache_tvm_native_c_api.cc)\n"
)
ANDROID_VULKAN_SOURCES_PATCH = (
    ANDROID_VULKAN_SOURCES_ANCHOR
    + "if(USE_VULKAN)\n"
    + "  file(GLOB TVM4J_VULKAN_RUNTIME_SRCS ${TVM_SOURCE_DIR}/src/runtime/vulkan/*.cc)\n"
    + "  target_sources(tvm4j_runtime_packed PRIVATE ${TVM4J_VULKAN_RUNTIME_SRCS})\n"
    + "  target_link_libraries(tvm4j_runtime_packed vulkan)\n"
    + "endif()\n"
)

VULKAN_STABLE_SERIALIZATION_ANCHOR = (
    "  std::unordered_map<std::string, SPIRVShader> smap;\n"
    "\n"
    "  std::string fmt;\n"
    "  stream.Read(&fmt);\n"
    "  ffi::Map<ffi::String, FunctionInfo> fmap;\n"
    "  TVM_FFI_ICHECK(stream.Read(&fmap));\n"
    "  stream.Read(&smap);\n"
    "  return VulkanModuleCreate(smap, fmap, \"\");\n"
)
VULKAN_STABLE_SERIALIZATION_PATCH = (
    "  // TVM 0.20 serializes Vulkan shaders as Map<String, Bytes>. The MLC\n"
    "  // Android runtime pinned by this app predates that wire-format change\n"
    "  // and otherwise reads the byte payload as a vector length (throwing the\n"
    "  // opaque libc++ error `vector`). String has the same length-prefixed\n"
    "  // representation as Bytes, so deserialize the portable map explicitly\n"
    "  // and rebuild the runtime's legacy in-memory shader map.\n"
    "  std::string fmt;\n"
    "  TVM_FFI_ICHECK(stream.Read(&fmt));\n"
    "  ffi::Map<ffi::String, FunctionInfo> fmap;\n"
    "  TVM_FFI_ICHECK(stream.Read(&fmap));\n"
    "  ffi::Map<ffi::String, ffi::String> serialized_smap;\n"
    "  TVM_FFI_ICHECK(stream.Read(&serialized_smap));\n"
    "\n"
    "  std::unordered_map<std::string, SPIRVShader> smap;\n"
    "  for (const auto& entry : serialized_smap) {\n"
    "    support::BytesInStream shader_stream(std::string(entry.second));\n"
    "    SPIRVShader shader;\n"
    "    TVM_FFI_ICHECK(shader_stream.Read(&shader));\n"
    "    smap.emplace(std::string(entry.first), std::move(shader));\n"
    "  }\n"
    "  return VulkanModuleCreate(std::move(smap), std::move(fmap), \"\");\n"
)

VULKAN_CALL_DIAGNOSTIC_ANCHOR = (
    "#define VULKAN_CALL(func)    \\\n"
    "  {                          \\\n"
    "    VkResult __e = (func);   \\\n"
    "    VULKAN_CHECK_ERROR(__e); \\\n"
    "  }\n"
)
VULKAN_CALL_DIAGNOSTIC_PATCH = (
    "#define VULKAN_CALL(func)                                                   \\\n"
    "  {                                                                         \\\n"
    "    VkResult __e = (func);                                                  \\\n"
    "    TVM_FFI_ICHECK(__e == VK_SUCCESS)                                      \\\n"
    "        << \"Vulkan call \" << #func << \" failed, code=\" << __e << \": \" \\\n"
    "        << vulkan::VKGetErrorString(__e);                                   \\\n"
    "  }\n"
)

VULKAN_PIPELINE_DIAGNOSTIC_ANCHOR = (
    "  VULKAN_CALL(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_cinfo, nullptr,\n"
    "                                       &(pe->pipeline)));\n"
)
VULKAN_PIPELINE_DIAGNOSTIC_PATCH = (
    "  VkResult pipeline_result = vkCreateComputePipelines(\n"
    "      device, VK_NULL_HANDLE, 1, &pipeline_cinfo, nullptr, &(pe->pipeline));\n"
    "  TVM_FFI_ICHECK(pipeline_result == VK_SUCCESS)\n"
    "      << \"Vulkan pipeline creation failed for kernel '\" << func_name\n"
    "      << \"', code=\" << pipeline_result << \": \"\n"
    "      << vulkan::VKGetErrorString(pipeline_result);\n"
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

    find_vulkan = source_dir / "3rdparty" / "tvm" / "cmake" / "utils" / "FindVulkan.cmake"
    vulkan_text = find_vulkan.read_text(encoding="utf-8")
    if ANDROID_VULKAN_PATCH not in vulkan_text:
        if ANDROID_VULKAN_ANCHOR in vulkan_text:
            vulkan_text = vulkan_text.replace(
                ANDROID_VULKAN_ANCHOR,
                ANDROID_VULKAN_PATCH,
                1,
            )
        elif ANDROID_VULKAN_LEGACY_PATCH in vulkan_text:
            vulkan_text = vulkan_text.replace(
                ANDROID_VULKAN_LEGACY_PATCH,
                ANDROID_VULKAN_PATCH,
                1,
            )
        else:
            raise RuntimeError(f"Could not find expected Android Vulkan block in {find_vulkan}")
    find_vulkan.write_text(vulkan_text, encoding="utf-8")

    vulkan_module = (
        source_dir
        / "3rdparty"
        / "tvm"
        / "src"
        / "runtime"
        / "vulkan"
        / "vulkan_module.cc"
    )
    vulkan_module_text = vulkan_module.read_text(encoding="utf-8")
    vulkan_module_text = replace_once(
        vulkan_module_text,
        VULKAN_STABLE_SERIALIZATION_ANCHOR,
        VULKAN_STABLE_SERIALIZATION_PATCH,
        vulkan_module,
    )
    vulkan_module.write_text(vulkan_module_text, encoding="utf-8")

    vulkan_common = (
        source_dir
        / "3rdparty"
        / "tvm"
        / "src"
        / "runtime"
        / "vulkan"
        / "vulkan_common.h"
    )
    vulkan_common_text = vulkan_common.read_text(encoding="utf-8")
    vulkan_common_text = replace_once(
        vulkan_common_text,
        VULKAN_CALL_DIAGNOSTIC_ANCHOR,
        VULKAN_CALL_DIAGNOSTIC_PATCH,
        vulkan_common,
    )
    vulkan_common.write_text(vulkan_common_text, encoding="utf-8")

    vulkan_wrapped_func = (
        source_dir
        / "3rdparty"
        / "tvm"
        / "src"
        / "runtime"
        / "vulkan"
        / "vulkan_wrapped_func.cc"
    )
    vulkan_wrapped_func_text = vulkan_wrapped_func.read_text(encoding="utf-8")
    vulkan_wrapped_func_text = replace_once(
        vulkan_wrapped_func_text,
        VULKAN_PIPELINE_DIAGNOSTIC_ANCHOR,
        VULKAN_PIPELINE_DIAGNOSTIC_PATCH,
        vulkan_wrapped_func,
    )
    vulkan_wrapped_func.write_text(vulkan_wrapped_func_text, encoding="utf-8")

    android_cmake = source_dir / "android" / "mlc4j" / "CMakeLists.txt"
    cmake_text = android_cmake.read_text(encoding="utf-8")
    if ANDROID_WHOLE_RUNTIME_LEGACY_PATCH in cmake_text:
        cmake_text = cmake_text.replace(
            ANDROID_WHOLE_RUNTIME_LEGACY_PATCH,
            ANDROID_WHOLE_RUNTIME_ANCHOR,
            1,
        )
    cmake_text = replace_once(
        cmake_text,
        ANDROID_VULKAN_SOURCES_ANCHOR,
        ANDROID_VULKAN_SOURCES_PATCH,
        android_cmake,
    )
    android_cmake.write_text(cmake_text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mlc-source-dir", required=True, type=Path)
    args = parser.parse_args()

    patch_runtime_source(args.mlc_source_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
