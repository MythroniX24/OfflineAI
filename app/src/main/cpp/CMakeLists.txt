cmake_minimum_required(VERSION 3.22.1)
project("offlineai")

# ── llama.cpp source directory (must exist before build) ──────────────────────
# Clone llama.cpp into app/src/main/cpp/llama.cpp/
# git clone https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# Check if llama.cpp sources exist
if(NOT EXISTS "${LLAMA_SRC_DIR}/llama.h")
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_SRC_DIR}\n"
        "Run: git clone https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp"
    )
endif()

# ── Compiler flags for performance ───────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-O3 -ffast-math -fno-exceptions -DNDEBUG)

# Disable GPU backends on Android (use CPU only for compatibility)
set(LLAMA_CUBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)

# ── Add llama.cpp as subdirectory ─────────────────────────────────────────────
add_subdirectory(${LLAMA_SRC_DIR} llama_build)

# ── JNI Bridge shared library ─────────────────────────────────────────────────
add_library(
    llama_android          # library name
    SHARED
    llama_bridge.cpp       # our JNI glue
)

target_include_directories(llama_android PRIVATE
    ${LLAMA_SRC_DIR}
    ${LLAMA_SRC_DIR}/common
)

# Link llama + android log
find_library(log-lib log)
target_link_libraries(llama_android
    llama
    common
    ${log-lib}
)
