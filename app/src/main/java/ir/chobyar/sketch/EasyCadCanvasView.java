package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Easy interaction layer for the advanced sketch tools.
 *
 * The CAD engine can stay powerful while the user sees only operations that are
 * valid for the current selection. This avoids requiring command knowledge or
 * remembering expert-only selection rules.
 */
public class EasyCadCanvasView extends ShaprLabCanvasView {

    private Field selectedField;
    private Field selectedObjectsField;
    private Field autoConstraintsField;

    public EasyCadCanvasView(Context context) {
        super(context);
        initEasyReflection();
    }

    private void initEasyReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            autoConstraintsField = field(ParametricSketchCanvasView.class, "autoConstraints");
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * Main user-facing constraint menu. It only shows tools that make sense for
     * the current selection, similar to an adaptive CAD menu.
     */
    public void showSmartConstraintMenu() {
        final List<Object> selection = selectionObjects();
        final List<Object> lines = lines(selection);
        final int curves = countCurves(selection);

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (selection.isEmpty()) {
            labels.add("✎ First text text Selection text");
            actions.add(this::showSelectionHelp);
        } else {
            if (!lines.isEmpty()) {
                labels.add("↔ Horizontal / Vertical");
                actions.add(() -> runParentString("applyHorizontalVerticalConstraint"));
            }

            if (lines.size() == 1) {
                labels.add("∠ Angle text Line");
                actions.add(() -> runParentVoid("showAngleEditor"));
            }

            if (lines.size() == 2 && selection.size() == 2) {
                labels.add("⊥ Perpendicular 90°");
                actions.add(() -> runParentString("applyPerpendicularConstraint"));

                labels.add("∥ Parallel");
                actions.add(() -> runParentString("applyParallelConstraint"));

                labels.add("● text text Line");
                actions.add(() -> runParentString("applyManualCoincident"));

                labels.add("M text text Midpoint Line");
                actions.add(this::applyEasyMidpoint);

                labels.add("= text text Length");
                actions.add(this::applyEqualEasy);

                labels.add("∠ Angle text text Line");
                actions.add(() -> runParentVoid("showAngleEditor"));
            }

            if (selection.size() >= 2 && allSameEqualFamily(selection)) {
                String equalLabel = lines.size() == selection.size()
                        ? "= text text Length All Linetext"
                        : "= text text Radius Circle/Arc";
                if (!labels.contains(equalLabel)) {
                    labels.add(equalLabel);
                    actions.add(this::applyEqualEasy);
                }
            }

            if (selection.size() == 2 && lines.size() == 1 && curves == 1) {
                labels.add("T Tangent Line text Circle/Arc");
                actions.add(this::applyTangentEasy);
            }

            if (selection.size() == 3 && lines.size() == 3) {
                labels.add("S Symmetry — Axis text text text text");
                actions.add(this::applyEasySymmetry);
            }

            labels.add("🔒 Lock / text text Selection");
            actions.add(() -> toast(toggleSelectedLock()));
        }

        labels.add(isAutoConstraintsEnabled()
                ? "⚙ Auto Constraints On — Offtext text"
                : "⚙ Auto Constraints Off — Ontext text");
        actions.add(this::toggleAutoConstraintsEasy);

        labels.add("☷ text Sketchtext");
        actions.add(() -> runParentVoid("showSketchManager"));

        labels.add("? Guidetext text text");
        actions.add(this::showSelectionHelp);

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(getContext())
                .setTitle("Constraints smart")
                .setMessage(smartSelectionHint())
                .setItems(items, (d, which) -> actions.get(which).run())
                .setNegativeButton("Close", null)
                .show();
    }

    public String smartSelectionHint() {
        List<Object> s = selectionObjects();
        if (s.isEmpty()) return "text not selected. First text text text Line/Circle text Selection text; text Toolstext text text Selection text text text.";

        List<Object> l = lines(s);
        int curves = countCurves(s);
        if (s.size() == 1 && l.size() == 1) return "1 Line selected: Angle, Horizontal/Vertical text Lock text text text.";
        if (s.size() == 2 && l.size() == 2) return "2 Line selected: Perpendicular, Parallel, text, Midpoint, Equal text Angle text Linetext text text text.";
        if (s.size() == 2 && l.size() == 1 && curves == 1) return "1 Line + 1 Circle/Arc: text Tangent / Tangent text Apply text.";
        if (s.size() == 3 && l.size() == 3) return "3 Line selected: text Line Midpoint text text Axis Symmetry text text text Symmetry text Apply text.";
        if (allSameEqualFamily(s) && s.size() >= 2) return s.size() + " text text selected: Equal text text text Dimensiontext Ready text.";
        return s.size() + " text selected. text Constraints text text text text Show text text.";
    }

    private void showSelectionHelp() {
        new AlertDialog.Builder(getContext())
                .setTitle("text text Constraints — text")
                .setMessage(
                        "1 Line: Angle text Horizontal/Vertical \n  \n " +
                        "2 Line: Perpendicular, Parallel, text, Angle text text text Midpoint \n  \n " +
                        "1 Line + 1 Circle/Arc: Tangent \n  \n " +
                        "2 text text Line/Circle text: Equal \n  \n " +
                        "3 Line: text text + text Axis = Symmetry \n  \n " +
                        "text text text text text text text; text text text Selection, text text text text text.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private void applyEqualEasy() {
        toast(applyEqualConstraint());
    }

    private void applyTangentEasy() {
        toast(applyTangentConstraint());
    }

    /**
     * For Midpoint, choose the longer of the two lines as the host automatically.
     * This removes the expert-only "selection order" requirement in the common case.
     */
    private void applyEasyMidpoint() {
        List<Object> l = lines(selectionObjects());
        if (l.size() != 2) {
            toast("text Midpoint, text Line text Selection text");
            return;
        }
        Object a = l.get(0), b = l.get(1);
        Object host = lineLength(a) >= lineLength(b) ? a : b;
        Object endpointLine = host == a ? b : a;
        setSmartSelection(endpointLine, host);
        toast(applyMidpointConstraint());
    }

    /**
     * For symmetry, detect the most central line and use it as the axis. This is
     * easier than requiring the user to know that the symmetry axis must be selected last.
     */
    private void applyEasySymmetry() {
        List<Object> l = lines(selectionObjects());
        if (l.size() != 3) {
            toast("text Symmetry, text Line text Selection text");
            return;
        }
        Object axis = bestAxis(l.get(0), l.get(1), l.get(2));
        List<Object> sides = new ArrayList<>();
        for (Object e : l) if (e != axis) sides.add(e);
        setSmartSelection(sides.get(0), sides.get(1), axis);
        toast(applySymmetryConstraint());
    }

    private Object bestAxis(Object a, Object b, Object c) {
        Object[] x = {a,b,c};
        float best = Float.MAX_VALUE;
        Object winner = c;
        for (int i=0;i<3;i++) {
            PointF m = midpoint(x[i]);
            PointF m1 = midpoint(x[(i+1)%3]);
            PointF m2 = midpoint(x[(i+2)%3]);
            if (m==null||m1==null||m2==null) continue;
            float cx=(m1.x+m2.x)/2f, cy=(m1.y+m2.y)/2f;
            float score=dist(m.x,m.y,cx,cy);
            if(score<best){best=score;winner=x[i];}
        }
        return winner;
    }

    private void toggleAutoConstraintsEasy() {
        try {
            if (autoConstraintsField == null) return;
            boolean next = !autoConstraintsField.getBoolean(this);
            autoConstraintsField.setBoolean(this, next);
            invalidate();
            toast(next ? "Auto Constraints On text" : "Auto Constraints Off text");
        } catch (Exception e) {
            toast("text Auto Constraints is unavailable");
        }
    }

    private boolean isAutoConstraintsEnabled() {
        try { return autoConstraintsField != null && autoConstraintsField.getBoolean(this); }
        catch (Exception e) { return true; }
    }

    private void runParentString(String methodName) {
        try {
            Method m = ParametricSketchCanvasView.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            Object r = m.invoke(this);
            if (r != null) toast(String.valueOf(r));
        } catch (Exception e) {
            toast("text text text text Selection text text text text");
        }
    }

    private void runParentVoid(String methodName) {
        try {
            Method m = ParametricSketchCanvasView.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception e) {
            toast("text Tools text is unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> selectionObjects() {
        try {
            if (selectedObjectsField != null) {
                List<Object> multi = (List<Object>) selectedObjectsField.get(this);
                if (multi != null && !multi.isEmpty()) return new ArrayList<>(multi);
            }
            List<Object> out = new ArrayList<>();
            Object one = selectedField == null ? null : selectedField.get(this);
            if (one != null) out.add(one);
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void setSmartSelection(Object... objects) {
        try {
            List<Object> multi = (List<Object>) selectedObjectsField.get(this);
            multi.clear();
            for (Object e : objects) if (e != null) multi.add(e);
            if (selectedField != null) selectedField.set(this, objects.length == 1 ? objects[0] : null);
            invalidate();
            dispatchWorkspaceState();
        } catch (Exception ignored) {
        }
    }

    private static List<Object> lines(List<Object> items) {
        List<Object> out = new ArrayList<>();
        for (Object e : items) if (isLine(e)) out.add(e);
        return out;
    }

    private static int countCurves(List<Object> items) {
        int n=0;
        for(Object e:items) if(isCurve(e)) n++;
        return n;
    }

    private static boolean allSameEqualFamily(List<Object> items) {
        if (items.size() < 2) return false;
        boolean line = isLine(items.get(0));
        boolean curve = isCurve(items.get(0));
        if (!line && !curve) return false;
        for (Object e : items) {
            if (line && !isLine(e)) return false;
            if (curve && !isCurve(e)) return false;
        }
        return true;
    }

    private static boolean isLine(Object e) {
        return e != null && "LineEntity".equals(e.getClass().getSimpleName());
    }

    private static boolean isCurve(Object e) {
        if (e == null) return false;
        String n=e.getClass().getSimpleName();
        return "CircleEntity".equals(n) || "ArcEntity".equals(n);
    }

    private static PointF midpoint(Object line) {
        try {
            Field x1=findField(line.getClass(),"x1"),y1=findField(line.getClass(),"y1"),x2=findField(line.getClass(),"x2"),y2=findField(line.getClass(),"y2");
            if(x1==null||y1==null||x2==null||y2==null)return null;
            return new PointF((x1.getFloat(line)+x2.getFloat(line))/2f,(y1.getFloat(line)+y2.getFloat(line))/2f);
        } catch(Exception e){return null;}
    }

    private static float lineLength(Object line) {
        try {
            Field x1=findField(line.getClass(),"x1"),y1=findField(line.getClass(),"y1"),x2=findField(line.getClass(),"x2"),y2=findField(line.getClass(),"y2");
            if(x1==null||y1==null||x2==null||y2==null)return 0f;
            return dist(x1.getFloat(line),y1.getFloat(line),x2.getFloat(line),y2.getFloat(line));
        } catch(Exception e){return 0f;}
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> c=type;
        while(c!=null){
            try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception ignored){c=c.getSuperclass();}
        }
        return null;
    }

    private static float dist(float x1,float y1,float x2,float y2){
        return (float)Math.hypot(x2-x1,y2-y1);
    }

    private void toast(String s) {
        if (s == null || s.trim().isEmpty()) return;
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}
