cmake_minimum_required(VERSION 3.22.1)

project(chobyar_brep LANGUAGES CXX)

add_library(
        chobyar_brep
        SHARED
        native_brep_jni.cpp
        occt_brep_with_face.cpp
)

target_compile_features(chobyar_brep PRIVATE cxx_std_17)
target_compile_options(chobyar_brep PRIVATE -Wall -Wextra -Wpedantic)

# CI builds a pinned OCCT 8.0.0 shared-library set for arm64-v8a. Other ABIs
# keep the native analytic fallback until their OCCT packages are enabled.
set(CHOBYAR_OCCT_ROOT "$ENV{CHOBYAR_OCCT_ROOT}")
if(ANDROID_ABI STREQUAL "arm64-v8a"
        AND CHOBYAR_OCCT_ROOT
        AND EXISTS "${CHOBYAR_OCCT_ROOT}/include/opencascade/Standard.hxx")
    file(GLOB CHOBYAR_OCCT_LIBS "${CHOBYAR_OCCT_ROOT}/lib/libTK*.so")
    if(CHOBYAR_OCCT_LIBS)
        target_compile_definitions(chobyar_brep PRIVATE CHOBYAR_WITH_OCCT=1)
        target_include_directories(chobyar_brep PRIVATE
                "${CHOBYAR_OCCT_ROOT}/include/opencascade")
        target_link_libraries(chobyar_brep PRIVATE ${CHOBYAR_OCCT_LIBS})
        message(STATUS "ChobYar: OCCT exact B-Rep enabled for ${ANDROID_ABI}")
    endif()
endif()

find_library(log-lib log)

target_link_libraries(
        chobyar_brep
        PRIVATE
        ${log-lib}
)
