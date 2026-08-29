#include "shape_store.h"

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
