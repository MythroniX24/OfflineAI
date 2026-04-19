cmake_minimum_required(VERSION 3.22.1)
project("offlineai")

set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(EXISTS "${LLAMA_SRC_DIR}/llama.h")
    message(STATUS "Found llama.h at root")
elseif(EXISTS "${LLAMA_SRC_DIR}/include/llama.h")
    message(STATUS "Found llama.h at include/")
else()
    message(FATAL_ERROR "llama.cpp sources not found at ${LLAMA_SRC_DIR}")
endif()

set(CMAKE_CXX_STANDARD 17)

# NO global -fno-exceptions or -ffast-math — llama.cpp needs both
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS  OFF CACHE BOOL "" FORCE)
set(LLAMA_CLBLAST OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL   OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC_DIR} llama_build)

add_library(llama_android SHARED llama_bridge.cpp)

target_compile_options(llama_android PRIVATE -O3 -DNDEBUG)

target_include_directories(llama_android PRIVATE
    ${LLAMA_SRC_DIR}
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/ggml/include
)

find_library(log-lib log)
# NOTE: 'common' removed — it's a static lib not exported by new llama.cpp
target_link_libraries(llama_android llama ${log-lib})
