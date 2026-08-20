// Keep the mature OCCT kernel implementation intact while compiling the
// exact face-topology extension in the same translation unit. This lets the
// extension reuse the private shape registry without exposing mutable native
// handles across translation units.
#include "occt_brep_jni.cpp"
#include "occt_face_topology_extension.inc"
