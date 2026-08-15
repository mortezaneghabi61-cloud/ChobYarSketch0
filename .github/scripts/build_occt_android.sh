#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OCCT_COMMIT="d3056ef80c9668f395da40f5fd7be186cae4501f"
OCCT_BASE="$ROOT/.occt"
OCCT_SRC="$OCCT_BASE/src"
OCCT_BUILD="$OCCT_BASE/build-arm64"
OCCT_INSTALL="$OCCT_BASE/install-arm64"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/27.0.12077973}"
CMAKE_BIN="${ANDROID_HOME:-}/cmake/3.22.1/bin/cmake"
NINJA_BIN="${ANDROID_HOME:-}/cmake/3.22.1/bin/ninja"

if [[ ! -x "$CMAKE_BIN" ]]; then CMAKE_BIN="cmake"; fi
if [[ ! -x "$NINJA_BIN" ]]; then NINJA_BIN="ninja"; fi
if [[ ! -f "$NDK_ROOT/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found at $NDK_ROOT" >&2
  exit 1
fi

mkdir -p "$OCCT_BASE"

# Cache-friendly: GitHub Actions restores install-arm64. Rebuild only when the
# exact OCCT shared libraries we need are missing.
if [[ ! -f "$OCCT_INSTALL/lib/libTKernel.so" || ! -f "$OCCT_INSTALL/lib/libTKBool.so" || ! -f "$OCCT_INSTALL/lib/libTKPrim.so" ]]; then
  rm -rf "$OCCT_SRC" "$OCCT_BUILD" "$OCCT_INSTALL"
  git clone --filter=blob:none https://github.com/Open-Cascade-SAS/OCCT.git "$OCCT_SRC"
  git -C "$OCCT_SRC" checkout --detach "$OCCT_COMMIT"

  "$CMAKE_BIN" -S "$OCCT_SRC" -B "$OCCT_BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_CPP_STANDARD=C++17 \
    -DBUILD_LIBRARY_TYPE=Shared \
    -DBUILD_SOVERSION_NUMBERS=0 \
    -DBUILD_MODULE_FoundationClasses=OFF \
    -DBUILD_MODULE_ModelingData=OFF \
    -DBUILD_MODULE_ModelingAlgorithms=OFF \
    -DBUILD_MODULE_Visualization=OFF \
    -DBUILD_MODULE_ApplicationFramework=OFF \
    -DBUILD_MODULE_DataExchange=OFF \
    -DBUILD_MODULE_Draw=OFF \
    -DBUILD_ADDITIONAL_TOOLKITS="TKPrim;TKBool" \
    -DUSE_TK=OFF \
    -DUSE_FREETYPE=OFF \
    -DUSE_TBB=OFF \
    -DUSE_GLES2=OFF \
    -DUSE_OPENGL=OFF \
    -DBUILD_USE_PCH=OFF \
    -DINSTALL_DIR="$OCCT_INSTALL" \
    -DINSTALL_DIR_LAYOUT=Unix \
    -DINSTALL_DIR_INCLUDE=include/opencascade \
    -DINSTALL_DIR_LIB=lib \
    -DINSTALL_DIR_BIN=bin \
    -DINSTALL_DIR_CMAKE=lib/cmake/opencascade

  "$CMAKE_BIN" --build "$OCCT_BUILD" --parallel 2
  "$CMAKE_BIN" --install "$OCCT_BUILD"
fi

mkdir -p "$JNI_DIR"
rm -f "$JNI_DIR"/libTK*.so
cp "$OCCT_INSTALL"/lib/libTK*.so "$JNI_DIR"/

echo "OCCT Android arm64 ready: $OCCT_INSTALL"
ls -1 "$JNI_DIR"/libTK*.so | sed 's#^.*/#  - #'