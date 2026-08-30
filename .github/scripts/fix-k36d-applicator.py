from pathlib import Path

p = Path('.github/scripts/apply-k36d-production.py')
s = p.read_text()
old = '''        assertEquals(0,((SketchGeometry.Line)d.entity("new")).a.distanceTo(new SketchGeometry.Point(10,0)),1e-9);\n'''
new = '''        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("new");\n        assertEquals(10,solved.a.xMm,1e-9); assertEquals(0,solved.a.yMm,1e-9);\n'''

if old in s:
    p.write_text(s.replace(old, new, 1))
elif new not in s:
    raise SystemExit('K3.6d geometry assertion is neither legacy nor expected fixed form')

Path('.github/scripts/fix-k36d-applicator.py').unlink()
