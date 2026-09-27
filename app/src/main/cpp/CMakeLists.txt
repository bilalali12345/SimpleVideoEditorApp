cmake_minimum_required(VERSION 3.22.1)
project("rnnoise_jni")

# ---------------------------------------------------------------------------
# The vendored RNNoise C library (Xiph.Org), compiled as a static library.
# File list matches the real Makefile.am RNNOISE_SOURCES, minus the x86-only
# SIMD files (src/x86/*), which are irrelevant on an ARM phone.
# ---------------------------------------------------------------------------
add_library(rnnoise STATIC
        rnnoise/src/denoise.c
        rnnoise/src/rnn.c
        rnnoise/src/pitch.c
        rnnoise/src/kiss_fft.c
        rnnoise/src/celt_lpc.c
        rnnoise/src/nnet.c
        rnnoise/src/nnet_default.c
        rnnoise/src/parse_lpcnet_weights.c
        rnnoise/src/rnnoise_data.c
        rnnoise/src/rnnoise_tables.c
)

target_include_directories(rnnoise PUBLIC
        rnnoise/include
        rnnoise/src
)

# Needed for the RNNOISE_EXPORT macro in rnnoise.h to resolve correctly.
target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)

# ---------------------------------------------------------------------------
# Our thin JNI bridge between Kotlin and the RNNoise C API.
# ---------------------------------------------------------------------------
add_library(rnnoise_jni SHARED
        rnnoise-jni.cpp
)

find_library(log-lib log)

target_link_libraries(rnnoise_jni
        rnnoise
        ${log-lib}
)