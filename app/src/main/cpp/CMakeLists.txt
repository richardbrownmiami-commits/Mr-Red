cmake_minimum_required(VERSION 3.22.1)
project(offlineai LANGUAGES C CXX)

include(FetchContent)

# Build only the RWKV CPU runtime. No llama.cpp, GPU backends, OpenMP,
# BLAS, tests, examples, or server are included in the Android build.
set(RWKV_TAG "master" CACHE STRING "Pinned RWKV.cpp revision")
FetchContent_Declare(
    rwkv
    GIT_REPOSITORY https://github.com/RWKV/rwkv.cpp.git
    GIT_TAG ${RWKV_TAG}
    GIT_SHALLOW TRUE
)
set(RWKV_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(RWKV_NATIVE OFF CACHE BOOL "" FORCE)
set(RWKV_AVX OFF CACHE BOOL "" FORCE)
set(RWKV_AVX2 OFF CACHE BOOL "" FORCE)
set(RWKV_AVX512 OFF CACHE BOOL "" FORCE)
set(RWKV_FMA OFF CACHE BOOL "" FORCE)
set(RWKV_ACCELERATE OFF CACHE BOOL "" FORCE)
set(RWKV_OPENBLAS OFF CACHE BOOL "" FORCE)
set(RWKV_CUBLAS OFF CACHE BOOL "" FORCE)
set(RWKV_CLBLAST OFF CACHE BOOL "" FORCE)
set(RWKV_HIPBLAS OFF CACHE BOOL "" FORCE)
set(RWKV_METAL OFF CACHE BOOL "" FORCE)
set(RWKV_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(RWKV_STANDALONE ON CACHE BOOL "" FORCE)
set(RWKV_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(rwkv)

add_library(offlineai SHARED native-lib.cpp)
target_include_directories(offlineai PRIVATE ${rwkv_SOURCE_DIR})
target_link_libraries(offlineai PRIVATE rwkv log android)
target_compile_options(offlineai PRIVATE -O3 -DNDEBUG -march=armv7-a -mfpu=neon -mfloat-abi=softfp)
