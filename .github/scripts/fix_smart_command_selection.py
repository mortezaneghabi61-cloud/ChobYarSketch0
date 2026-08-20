from pathlib import Path

# Keep command-created geometry selected after a stale multi-selection.
smart_path = Path('app/src/main/java/ir/chobyar/sketch/SmartCadCanvasView.java')
src = smart_path.read_text(encoding='utf-8')
old = '''                default:\n                    String result = super.executeCommand(normalized);\n                    syncFromBaseIfNeeded();\n                    return result;\n'''
new = '''                default:\n                    int beforeEntityCount = entities().size();\n                    String result = super.executeCommand(normalized);\n                    Object commandSelected = baseSelected();\n                    if (entities().size() > beforeEntityCount && commandSelected != null) {\n                        // A creation command makes its new entity the authoritative\n                        // single selection.  Do not let stale multi-selection state\n                        // clear CadCanvasView.selected immediately after creation.\n                        selectedObjects.clear();\n                        selectedObjects.add(commandSelected);\n                        syncBaseSelectionWithSmart();\n                    } else {\n                        syncFromBaseIfNeeded();\n                    }\n                    return result;\n'''
if new in src:
    print('Smart command selection contract already applied')
elif old in src:
    smart_path.write_text(src.replace(old, new, 1), encoding='utf-8')
    print('Applied Smart command selection contract')
else:
    raise SystemExit('Expected SmartCadCanvasView executeCommand default block not found')

# Expose one parent contract for persistent endpoint coincidence.
parent_path = Path('app/src/main/java/ir/chobyar/sketch/ChobYarShaprCanvasView.java')
parent = parent_path.read_text(encoding='utf-8')
parent_marker = '''    private void enforceConstraints() {\n'''
parent_contract = '''    /** Register a persistent endpoint coincidence for manual and automatic sketch tools. */\n    protected final void registerPersistentCoincident(Object a, int ai, Object b, int bi) {\n        if (!isLine(a) || !isLine(b)) return;\n        addCoincident(a, ai, b, bi);\n        enforceConstraints();\n    }\n\n'''
if parent_contract in parent:
    print('Persistent Coincident parent contract already applied')
elif parent_marker in parent:
    parent_path.write_text(parent.replace(parent_marker, parent_contract + parent_marker, 1), encoding='utf-8')
    print('Applied persistent Coincident parent contract')
else:
    raise SystemExit('Expected ChobYarShaprCanvasView enforceConstraints marker not found')

# Manual Coincident must register the same persistent relation used by auto-connect.
param_path = Path('app/src/main/java/ir/chobyar/sketch/ParametricSketchCanvasView.java')
param = param_path.read_text(encoding='utf-8')
manual_old = '''        setEndpoint(b, bi, target.x, target.y);\n        // The parent detects persistent endpoint coincidence on subsequent draws;\n        // we also keep our endpoint-to-line system clean by removing conflicting links.\n        removePointLink(b, bi);\n        invalidate();\n'''
manual_new = '''        setEndpoint(b, bi, target.x, target.y);\n        // Manual Coincident must create the same persistent relation as auto-connect.\n        // Register it explicitly instead of relying on a later draw-time proximity scan.\n        removePointLink(b, bi);\n        registerPersistentCoincident(a, ai, b, bi);\n        invalidate();\n'''
if manual_new in param:
    print('Manual Coincident persistence already applied')
elif manual_old in param:
    param_path.write_text(param.replace(manual_old, manual_new, 1), encoding='utf-8')
    print('Applied manual Coincident persistence')
else:
    raise SystemExit('Expected ParametricSketchCanvasView manual Coincident block not found')
