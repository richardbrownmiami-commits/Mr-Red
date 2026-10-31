cmake_minimum_required(VERSION 3.22.1)

project("native_llm")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# 32-bit ARMv7 NEON optimization for Android 11
if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -march=armv7-a -mfpu=neon-vfpv4 -mfloat-abi=softfp -funsafe-math-optimizations")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -march=armv7-a -mfpu=neon-vfpv4 -mfloat-abi=softfp -funsafe-math-optimizations")
endif()

if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(llama.cpp)
else()
    include(FetchContent)
    set(LLAMA_TAG "22dc605c4ead20e36f447cc67b55ef87e523bd55" CACHE STRING "Pinned llama.cpp commit")
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${LLAMA_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(
    native_llm
    SHARED
    bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(
    native_llm
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(
    native_llm
    PRIVATE
    llama
    ${log-lib}
    ${android-lib}
)
