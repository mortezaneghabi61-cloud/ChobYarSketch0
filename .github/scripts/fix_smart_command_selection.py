from pathlib import Path

# Keep this patch idempotent so CI can safely re-apply the production contract.
path = Path('app/src/main/java/ir/chobyar/sketch/SmartCadCanvasView.java')
src = path.read_text(encoding='utf-8')
old = '''                default:\n                    String result = super.executeCommand(normalized);\n                    syncFromBaseIfNeeded();\n                    return result;\n'''
new = '''                default:\n                    int beforeEntityCount = entities().size();\n                    String result = super.executeCommand(normalized);\n                    Object commandSelected = baseSelected();\n                    if (entities().size() > beforeEntityCount && commandSelected != null) {\n                        // A creation command makes its new entity the authoritative\n                        // single selection.  Do not let stale multi-selection state\n                        // clear CadCanvasView.selected immediately after creation.\n                        selectedObjects.clear();\n                        selectedObjects.add(commandSelected);\n                        syncBaseSelectionWithSmart();\n                    } else {\n                        syncFromBaseIfNeeded();\n                    }\n                    return result;\n'''
if new in src:
    print('Smart command selection contract already applied')
elif old in src:
    path.write_text(src.replace(old, new, 1), encoding='utf-8')
    print('Applied Smart command selection contract')
else:
    raise SystemExit('Expected SmartCadCanvasView executeCommand default block not found')
