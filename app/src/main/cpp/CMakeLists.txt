cmake_minimum_required(VERSION 3.18)
project(whisper_jni CXX)

# Build whisper.cpp + ggml as static libs and fold them into our single JNI shared lib.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # cross-compiling: no -march=native
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # no OpenMP runtime on stock NDK
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

find_library(log-lib log)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})
