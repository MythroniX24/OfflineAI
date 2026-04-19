cmake_minimum_required(VERSION 3.22.1)
project("offlineai")

set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# Accept both old and new llama.cpp header locations
if(EXISTS "${LLAMA_SRC_DIR}/llama.h")
    message(STATUS "Found llama.h at root")
elseif(EXISTS "${LLAMA_SRC_DIR}/include/llama.h")
    message(STATUS "Found llama.h at include/")
else()
    message(FATAL_ERROR "llama.cpp sources not found at ${LLAMA_SRC_DIR}")
endif()

set(CMAKE_CXX_STANDARD 17)

# ── IMPORTANT: Do NOT set -fno-exceptions or -ffast-math globally ─────────────
# llama.cpp's own files (gguf.cpp, ggml-cpu.c etc) need:
#   - exceptions enabled  (try/catch in gguf.cpp)
#   - finite math         (ggml-cpu vec.h checks __FINITE_MATH_ONLY__)
# Only apply performance flags to OUR bridge file via target_compile_options

# Disable GPU backends — CPU only for Android
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS  OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL   OFF CACHE BOOL "" FORCE)

# Add llama.cpp subdirectory — uses its own CMake flags, not ours
add_subdirectory(${LLAMA_SRC_DIR} llama_build)

# ── Our JNI bridge ────────────────────────────────────────────────────────────
add_library(llama_android SHARED llama_bridge.cpp)

# Apply optimization ONLY to our file — safe because we don't use try/catch
target_compile_options(llama_android PRIVATE -O3 -DNDEBUG)

target_include_directories(llama_android PRIVATE
    ${LLAMA_SRC_DIR}
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/common
    ${LLAMA_SRC_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(llama_android llama common ${log-lib})
