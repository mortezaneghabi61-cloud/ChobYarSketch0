#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/ir/chobyar/sketch"
CAD = SRC / "CadCanvasView.java"
ACTIVITY = SRC / "ChobYarActivity.java"


def patch_cad():
    src = CAD.read_text(encoding="utf-8")

    stack_anchor = "    private final ArrayDeque<List<Entity>> undoStack = new ArrayDeque<>();\n"
    stack_replacement = stack_anchor + "    private final ArrayDeque<List<Entity>> redoStack = new ArrayDeque<>();\n"
    if "redoStack = new ArrayDeque<>()" not in src:
        if stack_anchor not in src:
            raise RuntimeError("undo stack anchor not found")
        src = src.replace(stack_anchor, stack_replacement, 1)

    undo_anchor = '''    public void undo() {
        if (undoStack.isEmpty()) return;
        List<Entity> snapshot = undoStack.removeLast();
        entities.clear();
        for (Entity e : snapshot) entities.add(e.copy());
        selected = null;
        tool = TOOL_SELECT;
        invalidate();
    }
'''
    undo_replacement = '''    public boolean canUndoSketch() { return !undoStack.isEmpty(); }
    public boolean canRedoSketch() { return !redoStack.isEmpty(); }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.addLast(snapshotEntities());
        while (redoStack.size() > MAX_UNDO) redoStack.removeFirst();
        restoreSnapshot(undoStack.removeLast());
    }

    public boolean redoSketch() {
        if (redoStack.isEmpty()) return false;
        undoStack.addLast(snapshotEntities());
        while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
        restoreSnapshot(redoStack.removeLast());
        return true;
    }
'''
    if "public boolean redoSketch()" not in src:
        if undo_anchor not in src:
            raise RuntimeError("undo method anchor not found")
        src = src.replace(undo_anchor, undo_replacement, 1)

    save_anchor = '''    private void saveUndo(){
        List<Entity> snapshot=new ArrayList<>();
        for(Entity e:entities)snapshot.add(e.copy());
        undoStack.addLast(snapshot);
        while(undoStack.size()>MAX_UNDO)undoStack.removeFirst();
    }
'''
    save_replacement = '''    private List<Entity> snapshotEntities(){
        List<Entity> snapshot=new ArrayList<>();
        for(Entity e:entities)snapshot.add(e.copy());
        return snapshot;
    }

    private void restoreSnapshot(List<Entity> snapshot){
        entities.clear();
        for(Entity e:snapshot)entities.add(e.copy());
        selected=null;
        tool=TOOL_SELECT;
        drawing=false;
        draggingSelection=false;
        dragUndoSaved=false;
        activeHandle=-1;
        multiTouch=false;
        freePoints.clear();
        snapVisible=false;
        invalidate();
    }

    private void saveUndo(){
        undoStack.addLast(snapshotEntities());
        while(undoStack.size()>MAX_UNDO)undoStack.removeFirst();
        // Any new edit after Undo starts a new branch and invalidates Redo.
        redoStack.clear();
    }
'''
    if "private List<Entity> snapshotEntities()" not in src:
        if save_anchor not in src:
            raise RuntimeError("saveUndo anchor not found")
        src = src.replace(save_anchor, save_replacement, 1)

    CAD.write_text(src, encoding="utf-8")


def patch_activity():
    src = ACTIVITY.read_text(encoding="utf-8")

    src = src.replace(
        '        b.addView(topAction("↶",()->{cad.undo();status("برگشت");}),new LinearLayout.LayoutParams(dp(38),dp(48)));\n',
        '        b.addView(topAction("↶",this::undoAction),new LinearLayout.LayoutParams(dp(38),dp(48)));\n',
        1,
    )
    src = src.replace(
        '        b.addView(topAction("↷",()->status(cad.redoLastFeature())),new LinearLayout.LayoutParams(dp(38),dp(48)));\n',
        '        b.addView(topAction("↷",this::redoAction),new LinearLayout.LayoutParams(dp(38),dp(48)));\n',
        1,
    )
    src = src.replace(
        '        b.addView(miniAction("↶",()->{cad.undo();status("برگشت");}));\n',
        '        b.addView(miniAction("↶",this::undoAction));\n',
        1,
    )
    src = src.replace(
        '        b.addView(miniAction("↷",()->status(cad.redoLastFeature())));\n',
        '        b.addView(miniAction("↷",this::redoAction));\n',
        1,
    )

    if "private void redoAction()" not in src:
        anchor = "    private View bottomLeftControls(){\n"
        methods = '''    private void undoAction(){
        if(cad.is3DOverview()){
            status(cad.undoLastFeature());
            return;
        }
        if(!cad.canUndoSketch()){
            status("Undo خالی است");
            return;
        }
        cad.undo();
        status("برگشت");
    }

    private void redoAction(){
        if(cad.is3DOverview()){
            status(cad.redoLastFeature());
            return;
        }
        status(cad.redoSketch()?"جلو":"Redo خالی است");
    }

'''
        if anchor not in src:
            raise RuntimeError("activity redo routing anchor not found")
        src = src.replace(anchor, methods + anchor, 1)

    ACTIVITY.write_text(src, encoding="utf-8")


def validate():
    cad = CAD.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    required_cad = (
        "redoStack = new ArrayDeque<>()",
        "public boolean canUndoSketch()",
        "public boolean canRedoSketch()",
        "public boolean redoSketch()",
        "private List<Entity> snapshotEntities()",
        "redoStack.clear();",
    )
    for token in required_cad:
        if token not in cad:
            raise RuntimeError(f"Sketch redo contract missing: {token}")
    for token in ("this::undoAction", "this::redoAction", "cad.redoSketch()", "cad.redoLastFeature()"):
        if token not in activity:
            raise RuntimeError(f"Activity history routing missing: {token}")
    print("Sketch undo/redo routing patched and validated")


def main():
    patch_cad()
    patch_activity()
    validate()


if __name__ == "__main__":
    main()
