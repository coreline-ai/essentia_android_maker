cmake_minimum_required(VERSION 3.22.1)
project(audioanalyzer_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(essentia SHARED IMPORTED)
set_target_properties(essentia PROPERTIES
  IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libessentia.so
)

target_include_directories(essentia INTERFACE
  ${CMAKE_CURRENT_SOURCE_DIR}/include
  ${CMAKE_CURRENT_SOURCE_DIR}/include/eigen3
)

add_library(audioanalyzer_jni SHARED native_analyzer.cpp)

target_include_directories(audioanalyzer_jni PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/include
  ${CMAKE_CURRENT_SOURCE_DIR}/include/eigen3
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(audioanalyzer_jni
  PRIVATE
  essentia
  ${log-lib}
  ${android-lib}
  atomic
)
