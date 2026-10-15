cmake_minimum_required(VERSION 3.22.1)
project(offlineai LANGUAGES C CXX)

include(FetchContent)

set(LLAMA_TAG "b10903" CACHE STRING "Pinned llama.cpp revision")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# Keep this target portable across ARMv7 Android devices running in 32-bit mode.
# CPU-Z reports armv8l for this phone: that commonly means 32-bit ARM userspace
# on a 64-bit ARM kernel. Do not hardcode Cortex-A53 or another specific core,
# because the SoC may contain different ARM cores and the kernel label is not
# the CPU model. Keep generic ARMv7 + NEON flags and never enable ARMv8.2+
# features such as dotprod, FP16 vector arithmetic, i8mm, SVE or SME.
# GGML_NATIVE stays OFF so the build cannot inherit CI host CPU features.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(offlineai SHARED
    native-lib.cpp
)

target_include_directories(offlineai PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(offlineai PRIVATE llama log android)

target_compile_options(offlineai PRIVATE
    -O3
    -DNDEBUG
    -march=armv7-a
    -mfpu=neon-vfpv4
    -mfloat-abi=softfp
    -mno-unaligned-access
    -funsafe-math-optimizations
)
