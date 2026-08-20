from pathlib import Path
import runpy

# Apply the existing idempotent Manual 26.100 Boolean patch first.
runpy.run_path('.github/scripts/apply-manual26100-boolean-keep.py', run_name='__main__')

# The original helper used a Python triple-quoted replacement containing \n,
# which Python decoded into a physical newline inside a Java string literal.
# Normalize that generated source to the intended Java escape sequence.
target = Path('app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java')
text = target.read_text(encoding='utf-8')
bad = '.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'
good = r'.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'
if bad in text:
    text = text.replace(bad, good, 1)
elif good not in text:
    raise SystemExit('Boolean Keep UI newline anchor not found after patch')
target.write_text(text, encoding='utf-8')
print('Boolean Keep Java newline escaping verified')
