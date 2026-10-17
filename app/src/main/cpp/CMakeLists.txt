cmake_minimum_required(VERSION 3.22.1)
project(livesubs_whisper)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# o workflow do GitHub clona o whisper.cpp para esta pasta antes do build
add_subdirectory(whisper.cpp)

add_library(livesubs_whisper SHARED whisper_jni.cpp)
target_include_directories(livesubs_whisper PRIVATE whisper.cpp/include whisper.cpp/ggml/include)
target_link_libraries(livesubs_whisper whisper log)
