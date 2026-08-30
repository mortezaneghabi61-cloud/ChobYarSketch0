from pathlib import Path

path = Path("app/src/main/java/ir/chobyar/sketch/OcctStableCadCanvasView.java")
text = path.read_text()
old = 'if(r==null||r.score>180.0){edit.broken=true;edit.warning="Topology text was not found";return 0L;}'
new = 'if(r==null||!r.confident()){edit.broken=true;edit.warning="Topology text was not found";return 0L;}'
if text.count(old) != 1:
    raise SystemExit(f"expected one permissive History threshold, found {text.count(old)}")
text = text.replace(old, new, 1)
path.write_text(text)

# Fail closed if a second History-specific numeric threshold is ever reintroduced.
patched = path.read_text()
if 'r.score>180.0' in patched or 'r.score >= 180.0' in patched:
    raise SystemExit("permissive History topology threshold remains")
if '!r.confident()' not in patched:
    raise SystemExit("History does not consume Resolution.confident()")
print("History topology replay now shares Preview confidence policy")
