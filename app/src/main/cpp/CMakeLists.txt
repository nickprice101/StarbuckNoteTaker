cmake_minimum_required(VERSION 3.22.1)
project(llama_jni)

# ---------------------------------------------------------------------------
# Optional: real llama.cpp integration
#
# Place llama.cpp sources at app/src/main/cpp/llama.cpp/ (e.g. as a git
# submodule) and uncomment the block below.  The stub implementation in
# llama_jni.cpp guards its real code behind LLAMA_CPP_AVAILABLE so no
# source changes are needed once the sources are present.
#
# add_subdirectory(llama.cpp)
# target_compile_definitions(llama_jni PRIVATE LLAMA_CPP_AVAILABLE)
# target_link_libraries(llama_jni llama ggml)
# ---------------------------------------------------------------------------

find_library(log-lib log)
find_library(android-lib android)

add_library(
    llama_jni
    SHARED
    llama_jni.cpp
)

# Enable NEON SIMD on arm64-v8a for faster matrix math once real llama.cpp
# is integrated.
target_compile_options(llama_jni PRIVATE
    $<$<AND:$<CXX_COMPILER_ID:Clang>,$<STREQUAL:${ANDROID_ABI},arm64-v8a>>:-march=armv8-a+simd>
)

target_link_libraries(llama_jni
    ${log-lib}
    ${android-lib}
)
