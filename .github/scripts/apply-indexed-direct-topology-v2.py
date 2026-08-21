from pathlib import Path

p=Path('.github/scripts/apply-indexed-direct-topology.py')
src=p.read_text()
old="""'''          grep -q 'OCCT_FACE_SPHERE' \"$NATIVE\"\n          grep -q 'occtEdgeDescriptors' \"$TOPOLOGY\"\n          grep -q 'occtFaceDescriptors' \"$TOPOLOGY\"\n''',"""
new="""'''          grep -q 'OCCT_FACE_SPHERE' \"$NATIVE\"\n          grep -q 'OCCT_FACE_CONE' \"$NATIVE\"\n          grep -q 'OCCT_FACE_TORUS' \"$NATIVE\"\n          grep -q 'occtEdgeDescriptors' \"$TOPOLOGY\"\n          grep -q 'occtFaceDescriptors' \"$TOPOLOGY\"\n''',"""
if old not in src:
    raise RuntimeError('expected workflow patch pattern not found in base patcher')
src=src.replace(old,new,1)
exec(compile(src,str(p),'exec'),{'__name__':'__main__','__file__':str(p)})
