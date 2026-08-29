#pragma once

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
