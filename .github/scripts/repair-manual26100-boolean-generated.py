from pathlib import Path

TARGET = Path('app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java')
text = TARGET.read_text(encoding='utf-8')

# The generator uses Python triple-quoted replacement strings. A source token
# written as \n inside that Python string becomes a real newline in generated
# Java unless it is escaped once more. Repair that exact output deterministically
# before compilation and assert the Java source contains a literal backslash+n.
broken = '.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'
fixed = r'.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'

if broken in text:
    text = text.replace(broken, fixed, 1)

if broken in text:
    raise SystemExit('Boolean Keep generated Java still contains a raw newline in setMessage')
if fixed not in text:
    raise SystemExit('Boolean Keep generated Java message contract not found after repair')

TARGET.write_text(text, encoding='utf-8')
print('Boolean Keep generated Java string verified')
