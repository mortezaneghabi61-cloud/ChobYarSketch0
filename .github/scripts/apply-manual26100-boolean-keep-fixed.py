from pathlib import Path
import runpy

TARGET = Path('app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java')
text = TARGET.read_text(encoding='utf-8')

# The Keep implementation may already be persisted by a previous verified/self-
# committing CI attempt. Treat that as success instead of applying old anchors
# again. This keeps the diagnostic workflow idempotent.
already_applied = all(token in text for token in (
    'final boolean keepLeft;',
    'final boolean keepRight;',
    'showBooleanKeepOptions(',
    'KEEP_ORIGINALS',
    'KEEP_TARGET',
    'KEEP_TOOL',
))

if not already_applied:
    runpy.run_path('.github/scripts/apply-manual26100-boolean-keep.py', run_name='__main__')
    text = TARGET.read_text(encoding='utf-8')

# A Java source newline between the two pieces is invalid; the source must carry
# a literal backslash+n escape sequence inside the string.
bad = '.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'
good = r'.setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))'
if bad in text:
    text = text.replace(bad, good, 1)

required = (
    'final boolean keepLeft;',
    'final boolean keepRight;',
    'showBooleanKeepOptions(',
    'KEEP_ORIGINALS',
    'KEEP_TARGET',
    'KEEP_TOOL',
    good,
)
missing = [token for token in required if token not in text]
if missing:
    raise SystemExit('Boolean Keep production contract incomplete: ' + repr(missing))
if bad in text:
    raise SystemExit('Boolean Keep generated Java still contains a raw newline in setMessage')

TARGET.write_text(text, encoding='utf-8')
print('Boolean Keep production contract verified; patch is idempotent')
