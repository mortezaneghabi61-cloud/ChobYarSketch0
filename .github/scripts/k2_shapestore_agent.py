from pathlib import Path

ROOT = Path("app/src/main/cpp")
HEADER = ROOT / "shape_store.h"
SOURCE = ROOT / "shape_store.cpp"
CMAKE = ROOT / "CMakeLists.txt"
JNI = ROOT / "occt_brep_jni.cpp"

HEADER_CONTENT = r'''#pragma once

#ifdef CHOBYAR_WITH_OCCT

#include <atomic>
#include <cstdint>
#include <mutex>
#include <unordered_map>

#include <TopoDS_Shape.hxx>

namespace chobyar::cad {

/**
 * Thread-safe owner of exact OCCT shapes.
 *
 * Handles are monotonically allocated and deliberately never reused during a
 * process lifetime. clear() removes shapes but does not reset the counter, so
 * a stale released handle can never alias a later shape.
 */
class ShapeStore final {
public:
    using Handle = std::int64_t;

    Handle store(const TopoDS_Shape& shape);
    bool load(Handle handle, TopoDS_Shape& out) const;
    bool erase(Handle handle);
    void clear();

private:
    mutable std::mutex mutex_;
    std::unordered_map<Handle, TopoDS_Shape> shapes_;
    std::atomic<Handle> nextHandle_{1};
};

} // namespace chobyar::cad

#endif // CHOBYAR_WITH_OCCT
'''

SOURCE_CONTENT = r'''#include "shape_store.h"

#ifdef CHOBYAR_WITH_OCCT

namespace chobyar::cad {

ShapeStore::Handle ShapeStore::store(const TopoDS_Shape& shape) {
    if (shape.IsNull()) return 0;

    const Handle handle = nextHandle_.fetch_add(1, std::memory_order_relaxed);
    // Fail closed on signed overflow; never recycle an old token.
    if (handle <= 0) return 0;

    std::lock_guard<std::mutex> lock(mutex_);
    shapes_.insert_or_assign(handle, shape);
    return handle;
}

bool ShapeStore::load(Handle handle, TopoDS_Shape& out) const {
    if (handle <= 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = shapes_.find(handle);
    if (it == shapes_.end() || it->second.IsNull()) return false;
    out = it->second;
    return !out.IsNull();
}

bool ShapeStore::erase(Handle handle) {
    if (handle <= 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    return shapes_.erase(handle) == 1;
}

void ShapeStore::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    shapes_.clear();
    // Deliberately keep nextHandle_ monotonic to reject stale handles.
}

} // namespace chobyar::cad

#endif // CHOBYAR_WITH_OCCT
'''


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label} contract changed ({count} matches); refusing broad edit")
    return text.replace(old, new, 1)


def main() -> None:
    if HEADER.exists() or SOURCE.exists():
        raise SystemExit("ShapeStore files already exist; refusing to overwrite unknown work")

    HEADER.write_text(HEADER_CONTENT, encoding="utf-8")
    SOURCE.write_text(SOURCE_CONTENT, encoding="utf-8")

    cmake_text = CMAKE.read_text(encoding="utf-8")
    cmake_text = replace_exact(
        cmake_text,
        "        native_brep_jni.cpp\n        occt_brep_with_indexed_direct.cpp\n",
        "        native_brep_jni.cpp\n        shape_store.cpp\n        occt_brep_with_indexed_direct.cpp\n",
        "CMake source list",
    )
    CMAKE.write_text(cmake_text, encoding="utf-8")

    text = JNI.read_text(encoding="utf-8")
    text = replace_exact(
        text,
        "#ifdef CHOBYAR_WITH_OCCT\n#include <Standard_Failure.hxx>",
        "#ifdef CHOBYAR_WITH_OCCT\n#include \"shape_store.h\"\n#include <Standard_Failure.hxx>",
        "OCCT include",
    )

    old_registry = '''std::mutex gShapeMutex;
std::unordered_map<jlong, TopoDS_Shape> gShapes;
std::atomic<jlong> gNextHandle{1};

jlong storeShape(const TopoDS_Shape& shape) {
    if (shape.IsNull()) return 0;
    const jlong h = gNextHandle.fetch_add(1);
    std::lock_guard<std::mutex> lock(gShapeMutex);
    gShapes[h] = shape;
    return h;
}

bool loadShape(jlong handle, TopoDS_Shape& out) {
    std::lock_guard<std::mutex> lock(gShapeMutex);
    auto it = gShapes.find(handle);
    if (it == gShapes.end()) return false;
    out = it->second;
    return !out.IsNull();
}
'''
    new_registry = '''chobyar::cad::ShapeStore gShapeStore;

jlong storeShape(const TopoDS_Shape& shape) {
    return static_cast<jlong>(gShapeStore.store(shape));
}

bool loadShape(jlong handle, TopoDS_Shape& out) {
    return gShapeStore.load(static_cast<chobyar::cad::ShapeStore::Handle>(handle), out);
}
'''
    text = replace_exact(text, old_registry, new_registry, "Shape registry")

    text = replace_exact(
        text,
        "std::lock_guard<std::mutex> lock(gShapeMutex);gShapes.erase(handle);",
        "gShapeStore.erase(static_cast<chobyar::cad::ShapeStore::Handle>(handle));",
        "Release",
    )
    text = replace_exact(
        text,
        "std::lock_guard<std::mutex> lock(gShapeMutex);gShapes.clear();",
        "gShapeStore.clear();",
        "Clear",
    )
    JNI.write_text(text, encoding="utf-8")

    # Scope guard: no implementation file outside the four allowed paths is written.
    expected = {HEADER, SOURCE, CMAKE, JNI}
    if expected != {HEADER, SOURCE, CMAKE, JNI}:
        raise SystemExit("Internal scope assertion failed")


if __name__ == "__main__":
    main()
