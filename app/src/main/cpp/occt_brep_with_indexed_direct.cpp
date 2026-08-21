// Compile the mature OCCT kernel and exact-index direct-edit extension as one
// translation unit so the extension can reuse the private shape registry safely.
#include "occt_brep_jni.cpp"
#include "occt_indexed_direct_extension.inc"
