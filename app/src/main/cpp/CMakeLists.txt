cmake_minimum_required(VERSION 3.22.1)

project(chobyar_brep LANGUAGES CXX)

add_library(
        chobyar_brep
        SHARED
        native_brep_jni.cpp
)

target_compile_features(chobyar_brep PRIVATE cxx_std_17)
target_compile_options(chobyar_brep PRIVATE -Wall -Wextra -Wpedantic)

find_library(log-lib log)

target_link_libraries(
        chobyar_brep
        ${log-lib}
)
