cmake_minimum_required(VERSION 3.22.1)
project("offlineai")

set(CMAKE_CXX_STANDARD 17)
set(LIB_DIR ${CMAKE_SOURCE_DIR}/lib)

include_directories(
    ${LIB_DIR}
    ${LIB_DIR}/common
    ${LIB_DIR}/common/jinja
    ${LIB_DIR}/ggml-cpu
    ${LIB_DIR}/tools/mtmd
)

# Collect sources
file(GLOB MODEL_FILES      ${LIB_DIR}/models/*.cpp)
file(GLOB GGML_CPU_C       ${LIB_DIR}/ggml-cpu/*.c)
file(GLOB GGML_CPU_CPP     ${LIB_DIR}/ggml-cpu/*.cpp)
file(GLOB GGML_CPU_AMX     ${LIB_DIR}/ggml-cpu/amx/*.cpp)
file(GLOB LLAMA_FILES      ${LIB_DIR}/llama*.cpp)
file(GLOB COMMON_FILES     ${LIB_DIR}/common/*.cpp)
file(GLOB JINJA_FILES      ${LIB_DIR}/common/jinja/*.cpp)

set(ALL_SOURCES
    # Core ggml
    ${LIB_DIR}/ggml.c
    ${LIB_DIR}/ggml-alloc.c
    ${LIB_DIR}/ggml-backend.cpp
    ${LIB_DIR}/ggml-backend-dl.cpp
    ${LIB_DIR}/ggml-backend-reg.cpp
    ${LIB_DIR}/ggml-opt.cpp
    ${LIB_DIR}/ggml-threading.cpp
    ${LIB_DIR}/ggml-quants.c
    ${LIB_DIR}/gguf.cpp
    ${LIB_DIR}/anyascii.c
    # ggml-cpu
    ${GGML_CPU_C}
    ${GGML_CPU_CPP}
    ${GGML_CPU_AMX}
    # llama core
    ${LLAMA_FILES}
    ${LIB_DIR}/unicode-data.cpp
    ${LIB_DIR}/unicode.cpp
    # common
    ${COMMON_FILES}
    ${JINJA_FILES}
    # models
    ${MODEL_FILES}
    # our JNI bridge
    llama_bridge.cpp
)

find_library(LOG_LIB log)

function(build_lib TARGET ARCH CPU_FLAGS)
    if(NOT ${ARCH} STREQUAL "generic")
        set(ARCH_SRCS
            ${LIB_DIR}/ggml-cpu/arch/${ARCH}/quants.c
            ${LIB_DIR}/ggml-cpu/arch/${ARCH}/repack.cpp
        )
    endif()

    add_library(${TARGET} SHARED ${ALL_SOURCES} ${ARCH_SRCS})

    target_link_libraries(${TARGET} ${LOG_LIB} android)

    target_compile_options(${TARGET} PRIVATE
        -O3 -DNDEBUG
        -fno-finite-math-only      # required by ggml-cpu/vec.h
        -D_GNU_SOURCE
        -pthread
        ${CPU_FLAGS}
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
    )
    if(${ARCH} STREQUAL "generic")
        target_compile_options(${TARGET} PRIVATE -DLM_GGML_CPU_GENERIC)
    endif()
    target_link_options(${TARGET} PRIVATE -Wl,--gc-sections)
endfunction()

build_lib("llama_android" "generic" "")

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_lib("llama_android_v8"           "arm" "-march=armv8-a")
    build_lib("llama_android_v8_2_dotprod" "arm" "-march=armv8.2-a+dotprod")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    build_lib("llama_android_x86" "x86" "-march=x86-64;-msse4.2;-mpopcnt")
endif()
