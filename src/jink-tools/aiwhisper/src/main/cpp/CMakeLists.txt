cmake_minimum_required(VERSION 3.22)
project(whisperjni C CXX)

# whisper.cpp source, fetched by native-sources.gradle (or passed in for host builds).
if(NOT DEFINED WHISPER_SRC)
  message(FATAL_ERROR "Set -DWHISPER_SRC=/path/to/whisper.cpp")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID)
  # ARMv8.2 with dot-product instructions: every Snapdragon from the last several years, including
  # the Note Air 6C's Q6690. They make the int8 matrix maths several times faster.
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_SRC} whisper EXCLUDE_FROM_ALL)

add_library(whisperjni SHARED whisper_jni.cpp)
target_link_libraries(whisperjni PRIVATE whisper)
if(ANDROID)
  target_link_libraries(whisperjni PRIVATE log)
else()
  # Host build, for running the JVM tests on a desktop.
  find_package(JNI REQUIRED)
  target_include_directories(whisperjni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
# Keep this library's private copy of ggml private, so it can't clash with the one inside llamajni.
target_link_options(whisperjni PRIVATE "-Wl,--exclude-libs,ALL")
set_target_properties(whisperjni PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
