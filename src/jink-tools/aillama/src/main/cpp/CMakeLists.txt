cmake_minimum_required(VERSION 3.22)
project(llamajni C CXX)

# llama.cpp source, fetched by native-sources.gradle (or passed in for host builds).
if(NOT DEFINED LLAMA_SRC)
  message(FATAL_ERROR "Set -DLLAMA_SRC=/path/to/llama.cpp")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(opt LLAMA_BUILD_TESTS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_TOOLS LLAMA_BUILD_COMMON
            LLAMA_BUILD_APP LLAMA_BUILD_MTMD LLAMA_BUILD_UI LLAMA_BUILD_HTML LLAMA_OPENSSL LLAMA_LLGUIDANCE)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID)
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)

add_library(llamajni SHARED llama_jni.cpp)
target_link_libraries(llamajni PRIVATE llama)
if(ANDROID)
  target_link_libraries(llamajni PRIVATE log)
else()
  find_package(JNI REQUIRED)
  target_include_directories(llamajni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
# Keep this library's private copy of ggml private, so it can't clash with the one inside whisperjni.
target_link_options(llamajni PRIVATE "-Wl,--exclude-libs,ALL")
set_target_properties(llamajni PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
