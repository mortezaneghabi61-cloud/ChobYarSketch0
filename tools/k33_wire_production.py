from pathlib import Path

p = Path('app/src/main/java/ir/chobyar/sketch/ChobYarActivity.java')
s = p.read_text(encoding='utf-8')
replacements = {
    'private Shapr3DGuideCadCanvasView cad;': 'private K33MirroredCadCanvasView cad;',
    'cad=new Shapr3DGuideCadCanvasView(this);': 'cad=new K33MirroredCadCanvasView(this);',
}
for old, new in replacements.items():
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one occurrence of {old!r}, found {count}')
    s = s.replace(old, new)
p.write_text(s, encoding='utf-8')
print('K3.3 production canvas wiring applied')
