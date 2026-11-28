cmake_minimum_required(VERSION 3.22.1)
project(mrred_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH armv7-a CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b5046
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(mrred_llm SHARED bridge.cpp)
find_library(log-lib log)
target_include_directories(mrred_llm PRIVATE ${llama_SOURCE_DIR}/include ${llama_SOURCE_DIR}/ggml/include)
target_link_libraries(mrred_llm PRIVATE llama ${log-lib})
