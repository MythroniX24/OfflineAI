cmake_minimum_required(VERSION 3.22.1)
project("offlineai")

set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# ── Newer llama.cpp moved llama.h to include/llama.h ─────────────────────────
# Check both old path (root) and new path (include/)
if(EXISTS "${LLAMA_SRC_DIR}/llama.h")
    set(LLAMA_H_FOUND TRUE)
elseif(EXISTS "${LLAMA_SRC_DIR}/include/llama.h")
    set(LLAMA_H_FOUND TRUE)
else()
    set(LLAMA_H_FOUND FALSE)
endif()

if(NOT LLAMA_H_FOUND)
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_SRC_DIR}\n"
        "Run: git clone https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp"
    )
endif()

# ── Compiler flags ────────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-O3 -ffast-math -fno-exceptions -DNDEBUG)

# Disable GPU backends on Android (CPU only)
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS  OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL   OFF CACHE BOOL "" FORCE)

# ── Add llama.cpp ─────────────────────────────────────────────────────────────
add_subdirectory(${LLAMA_SRC_DIR} llama_build)

# ── JNI Bridge ───────────────────────────────────────────────────────────────
add_library(llama_android SHARED llama_bridge.cpp)

# Include both old and new header paths
target_include_directories(llama_android PRIVATE
    ${LLAMA_SRC_DIR}
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/common
    ${LLAMA_SRC_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(llama_android llama common ${log-lib})
