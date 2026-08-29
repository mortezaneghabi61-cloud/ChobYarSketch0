package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * In-app CAD laboratory. The point is to rebuild difficult workflows with the
 * real ChobYar engine, not a mock screen, so every missing behavior becomes a
 * reusable product feature.
 */
public class ShaprLabCanvasView extends ParametricSketchCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float CHIP_H = 46f;

    private Field selectedField;
    private Field entitiesField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;

    private final List<EqualRelation> equalRelations = new ArrayList<>();
    private final List<SymmetryRelation> symmetryRelations = new ArrayList<>();
    private final List<MidpointRelation> midpointRelations = new ArrayList<>();
    private final List<TangentRelation> tangentRelations = new ArrayList<>();

    private final RectF labChip = new RectF();
    private boolean labPressed;
    private boolean labBuilt;

    private final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint relationText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprLabCanvasView(Context context) {
        super(context);
        initReflection();
        chipFill.setColor(Color.argb(248, 245, 249, 255));
        chipFill.setStyle(Paint.Style.FILL);
        chipStroke.setColor(Color.rgb(70, 125, 220));
        chipStroke.setStyle(Paint.Style.STROKE);
        chipStroke.setStrokeWidth(2f);
        chipText.setColor(Color.rgb(35, 80, 160));
        chipText.setTextSize(20f);
        chipText.setTextAlign(Paint.Align.CENTER);
        relationText.setColor(Color.rgb(35, 105, 205));
        relationText.setTextSize(20f);
        relationText.setTextAlign(Paint.Align.CENTER);
    }

    private void initReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            entitiesField = field(CadCanvasView.class, "entities");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------
    // Advanced constraints learned/tested in the lab
    // ------------------------------------------------------------------

    public String applyEqualConstraint() {
        List<Object> s = selectionObjects();
        if (s.size() < 2) return "text Equal text text Line text text Circle/Arc text Selection text";
        boolean lines = isLine(s.get(0));
        boolean curves = isCurve(s.get(0));
        if (!lines && !curves) return "Equal text Line, Circle text Arc text text";
        for (Object e : s) {
            if (lines && !isLine(e)) return "All Selectiontext text Line text";
            if (curves && !isCurve(e)) return "All Selectiontext text Circle/Arc text";
        }
        EqualRelation r = new EqualRelation(new ArrayList<>(s), lines);
        equalRelations.add(r);
        r.enforce();
        invalidate();
        return lines ? "Equal: Length Linetext text text" : "Equal: Radiustext text text";
    }

    /** Select two lines, then the axis as the third selected line. */
    public String applySymmetryConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 3) return "Symmetry: text Line text Then Line Axis text Selection text";
        SymmetryRelation r = new SymmetryRelation(lines.get(0), lines.get(1), lines.get(2));
        symmetryRelations.add(r);
        r.enforce();
        invalidate();
        return "Symmetry Apply text";
    }

    /** First line contributes its nearest endpoint; second line is the host. */
    public String applyMidpointConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "Midpoint: text Line text Selection text";
        PointF m = midpoint(lines.get(1));
        if (m == null) return "Midpoint text text";
        MidpointRelation r = new MidpointRelation(lines.get(0), nearestEndpointIndex(lines.get(0), m), lines.get(1));
        midpointRelations.add(r);
        r.enforce();
        invalidate();
        return "Midpoint: text Line First text Midpoint Line text text text";
    }

    public String applyTangentConstraint() {
        List<Object> s = selectionObjects();
        if (s.size() != 2) return "Tangent: text Line text text Circle/Arc text Selection text";
        Object line = null, curve = null;
        for (Object e : s) {
            if (isLine(e)) line = e;
            else if (isCurve(e)) curve = e;
        }
        if (line == null || curve == null) return "Tangent text text Line text text Circle/Arc text text";
        TangentRelation r = createTangentRelation(line, curve);
        if (r == null) return "Tangent text text; Point text Line text text text text";
        tangentRelations.add(r);
        r.enforce();
        invalidate();
        return "Tangent Apply text";
    }

    // ------------------------------------------------------------------
    // Difficult real-workflow regression model
    // ------------------------------------------------------------------

    /**
     * Recreates the difficult sketch/setup stage of Shapr3D's official Action
     * Camera Mount tutorial using ChobYar primitives and constraints. Explicit
     * tutorial values are preserved: 9, 4.5, 3, 6, 1.5 mm and 135 degrees.
     * ChobYar command input is cm, therefore the values below are divided by 10.
     */
    public String buildActionMountLab() {
        if (labBuilt) return "Action Mount LAB text createdtext";

        createSketchSpace("LAB • Action Mount");

        Object axis = make("LINE 0 -0.30 0 1.65");
        Object base = make("LINE -0.90 0 0.90 0");
        Object baseOffset = make("LINE -0.90 0.30 0.90 0.30");

        Object li = make("LINE -0.90 0 -0.90 0.60");
        Object la = make("LINE -0.90 0.60 -1.20 0.90");
        Object lo = make("LINE -1.20 0.90 -1.20 1.35");
        Object lOffset = make("LINE -0.75 0 -0.75 0.60");

        Object ri = make("LINE 0.90 0 0.90 0.60");
        Object ra = make("LINE 0.90 0.60 1.20 0.90");
        Object ro = make("LINE 1.20 0.90 1.20 1.35");
        Object rOffset = make("LINE 0.75 0 0.75 0.60");

        Object rib = make("LINE 0 0 0 0.60");
        Object leftBoss = make("CIRCLE -1.50 1.35 0.30");
        Object rightBoss = make("CIRCLE 1.50 1.35 0.30");

        // Directional constraints.
        setSelection(axis); applyHorizontalVerticalConstraint();
        setSelection(base, baseOffset); applyHorizontalVerticalConstraint();
        setSelection(li, lo, lOffset, ri, ro, rOffset, rib); applyHorizontalVerticalConstraint();

        // Driving angular dimensions from the tutorial.
        setSelection(la); setSelectedLineAngle(135f);
        setSelection(ra); setSelectedLineAngle(45f);

        // Persistent symmetry, equality, midpoint and tangency.
        setSelection(la, ra, axis); applySymmetryConstraint();
        setSelection(leftBoss, rightBoss); applyEqualConstraint();
        setSelection(rib, base); applyMidpointConstraint();
        setSelection(lo, leftBoss); applyTangentConstraint();
        setSelection(ro, rightBoss); applyTangentConstraint();

        // Stable driving axis.
        setSelection(axis); toggleSelectedLock();

        // A second independent sketch emulates the later relief/projection stage.
        createSketchSpace("LAB • Relief");
        Object reliefAxis = make("LINE 0 1.55 0 2.15");
        Object rt = make("LINE -0.40 1.70 0.40 1.70");
        Object rl = make("LINE -0.40 1.70 -0.70 2.00");
        Object rr = make("LINE 0.40 1.70 0.70 2.00");
        Object rb = make("LINE -0.70 2.00 0.70 2.00");
        setSelection(reliefAxis, rt, rb); applyHorizontalVerticalConstraint();
        setSelection(rl); setSelectedLineAngle(135f);
        setSelection(rr); setSelectedLineAngle(45f);
        setSelection(rl, rr, reliefAxis); applySymmetryConstraint();
        setSelection(reliefAxis); toggleSelectedLock();

        // Back to the driving sketch; both sketches remain independently managed.
        int n = sketchCount();
        if (n >= 2) switchSketchSpace(n - 2);
        clearSelection();
        fitAll();
        labBuilt = true;
        invalidate();
        Toast.makeText(getContext(),
                "LAB created: Angle, Offset, Symmetry, Equal, Midpoint, Tangent text Lock text text text",
                Toast.LENGTH_LONG).show();
        return "Action Mount LAB created";
    }

    private Object make(String command) {
        executeCommand(command);
        return selectedObject();
    }

    // ------------------------------------------------------------------
    // Lightweight persistent solver
    // ------------------------------------------------------------------

    private class EqualRelation {
        final List<Object> items;
        final boolean lineMode;
        final float value;
        EqualRelation(List<Object> items, boolean lineMode) {
            this.items = items;
            this.lineMode = lineMode;
            this.value = lineMode ? lineLength(items.get(0)) : Math.abs(safeGet(items.get(0), "r"));
        }
        void enforce() {
            for (int i = 1; i < items.size(); i++) {
                Object e = items.get(i);
                if (lineMode) setLineLength(e, value, connectedPivotIndex(e));
                else setFloat(e, "r", value);
            }
        }
        boolean valid(List<Object> all) {
            for (Object e : items) if (!containsIdentity(all, e)) return false;
            return true;
        }
    }

    private class SymmetryRelation {
        final Object source, mirror, axis;
        SymmetryRelation(Object source, Object mirror, Object axis) {
            this.source = source; this.mirror = mirror; this.axis = axis;
        }
        void enforce() {
            PointF s0 = endpoint(source, 0), s1 = endpoint(source, 1);
            PointF a0 = endpoint(axis, 0), a1 = endpoint(axis, 1);
            PointF m0 = endpoint(mirror, 0), m1 = endpoint(mirror, 1);
            if (s0==null||s1==null||a0==null||a1==null||m0==null||m1==null) return;
            PointF r0 = reflect(s0, a0, a1), r1 = reflect(s1, a0, a1);
            float same = dist(m0.x,m0.y,r0.x,r0.y)+dist(m1.x,m1.y,r1.x,r1.y);
            float swap = dist(m0.x,m0.y,r1.x,r1.y)+dist(m1.x,m1.y,r0.x,r0.y);
            if (same <= swap) {
                setEndpoint(mirror,0,r0.x,r0.y); setEndpoint(mirror,1,r1.x,r1.y);
            } else {
                setEndpoint(mirror,0,r1.x,r1.y); setEndpoint(mirror,1,r0.x,r0.y);
            }
        }
        boolean valid(List<Object> all) {
            return containsIdentity(all,source)&&containsIdentity(all,mirror)&&containsIdentity(all,axis);
        }
    }

    private class MidpointRelation {
        final Object endpointLine, host;
        final int endpointIndex;
        MidpointRelation(Object endpointLine, int endpointIndex, Object host) {
            this.endpointLine=endpointLine; this.endpointIndex=endpointIndex; this.host=host;
        }
        void enforce() {
            PointF m=midpoint(host);
            if(m!=null)setEndpoint(endpointLine,endpointIndex,m.x,m.y);
        }
        boolean valid(List<Object> all) {
            return containsIdentity(all,endpointLine)&&containsIdentity(all,host);
        }
    }

    private class TangentRelation {
        final Object line, curve;
        final int movingIndex;
        TangentRelation(Object line, Object curve, int movingIndex) {
            this.line=line; this.curve=curve; this.movingIndex=movingIndex;
        }
        void enforce() {
            PointF c=curveCenter(curve);
            PointF moving=endpoint(line,movingIndex), fixed=endpoint(line,1-movingIndex);
            float r=Math.abs(safeGet(curve,"r"));
            if(c==null||moving==null||fixed==null||r<=0f)return;
            PointF t=tangentPoint(fixed,c,r,moving);
            if(t!=null)setEndpoint(line,movingIndex,t.x,t.y);
        }
        boolean valid(List<Object> all) {
            return containsIdentity(all,line)&&containsIdentity(all,curve);
        }
    }

    private TangentRelation createTangentRelation(Object line, Object curve) {
        PointF c=curveCenter(curve), p0=endpoint(line,0), p1=endpoint(line,1);
        float r=Math.abs(safeGet(curve,"r"));
        if(c==null||p0==null||p1==null||r<=0f)return null;
        float d0=dist(p0.x,p0.y,c.x,c.y), d1=dist(p1.x,p1.y,c.x,c.y);
        int moving=d0<=d1?0:1;
        PointF fixed=moving==0?p1:p0;
        if(dist(fixed.x,fixed.y,c.x,c.y)<=r+0.0001f)return null;
        return new TangentRelation(line,curve,moving);
    }

    private void enforceRelations() {
        List<Object> all=entities();
        Iterator<EqualRelation> ei=equalRelations.iterator();
        while(ei.hasNext()){EqualRelation r=ei.next();if(!r.valid(all))ei.remove();else r.enforce();}
        Iterator<SymmetryRelation> si=symmetryRelations.iterator();
        while(si.hasNext()){SymmetryRelation r=si.next();if(!r.valid(all))si.remove();else r.enforce();}
        Iterator<MidpointRelation> mi=midpointRelations.iterator();
        while(mi.hasNext()){MidpointRelation r=mi.next();if(!r.valid(all))mi.remove();else r.enforce();}
        Iterator<TangentRelation> ti=tangentRelations.iterator();
        while(ti.hasNext()){TangentRelation r=ti.next();if(!r.valid(all))ti.remove();else r.enforce();}
    }

    // ------------------------------------------------------------------
    // On-canvas LAB controls
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        enforceRelations();
        super.onDraw(canvas);
        drawLabChip(canvas);
        drawRelationBadges(canvas);
    }

    private void drawLabChip(Canvas canvas) {
        float w=labBuilt?155f:190f;
        float right=getWidth()-12f;
        labChip.set(right-w,204f,right,204f+CHIP_H);
        canvas.drawRoundRect(labChip,14f,14f,chipFill);
        canvas.drawRoundRect(labChip,14f,14f,chipStroke);
        canvas.drawText(labBuilt?"LAB • Action Mount":"LAB • text text",labChip.centerX(),labChip.centerY()+7f,chipText);
    }

    private void drawRelationBadges(Canvas canvas) {
        for(EqualRelation r:equalRelations)for(Object e:r.items)badge(canvas,e,"=");
        for(SymmetryRelation r:symmetryRelations){badge(canvas,r.source,"S");badge(canvas,r.mirror,"S");}
        for(MidpointRelation r:midpointRelations)badge(canvas,r.endpointLine,"M");
        for(TangentRelation r:tangentRelations)badge(canvas,r.line,"T");
    }

    private void badge(Canvas canvas,Object e,String text) {
        PointF c=center(e);
        if(c==null)return;
        PointF s=worldToScreen(c.x,c.y);
        canvas.drawText(text,s.x+18f,s.y-18f,relationText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int a=event.getActionMasked();
        float x=event.getX(),y=event.getY();
        if(a==MotionEvent.ACTION_DOWN&&labChip.contains(x,y)){labPressed=true;return true;}
        if(labPressed){
            if(a==MotionEvent.ACTION_UP){labPressed=false;if(labChip.contains(x,y))showLabMenu();}
            else if(a==MotionEvent.ACTION_CANCEL)labPressed=false;
            return true;
        }
        boolean handled=super.onTouchEvent(event);
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){enforceRelations();invalidate();}
        return handled;
    }

    private void showLabMenu() {
        String[] items={
                labBuilt?"✓ Action Mount LAB createdtext":"Create Action Mount LAB",
                "= Equal / text",
                "S Symmetry / Symmetry",
                "M Midpoint / Midpoint Line",
                "T Tangent / Tangent",
                "Fit All"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("ChobYar • Shapr LAB")
                .setMessage("text text text text text text ChobYar created text until text Toolstext text text.")
                .setItems(items,(d,w)->{
                    String result;
                    if(w==0)result=buildActionMountLab();
                    else if(w==1)result=applyEqualConstraint();
                    else if(w==2)result=applySymmetryConstraint();
                    else if(w==3)result=applyMidpointConstraint();
                    else if(w==4)result=applyTangentConstraint();
                    else{fitAll();result="Fit All";}
                    Toast.makeText(getContext(),result,Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close",null).show();
    }

    @Override
    public void clearAll() {
        super.clearAll();
        clearRelations();
        labBuilt=false;
    }

    @Override
    public void undo() {
        super.undo();
        // Base undo clones entity instances; identity relations must not point
        // at pre-undo objects.
        clearRelations();
        labBuilt=false;
    }

    private void clearRelations() {
        equalRelations.clear(); symmetryRelations.clear(); midpointRelations.clear(); tangentRelations.clear();
    }

    // ------------------------------------------------------------------
    // Reflection and geometry helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try{return entitiesField==null?new ArrayList<>(): (List<Object>)entitiesField.get(this);}
        catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private List<Object> smartSelection() {
        try{return selectedObjectsField==null?new ArrayList<>(): (List<Object>)selectedObjectsField.get(this);}
        catch(Exception e){return new ArrayList<>();}
    }

    private List<Object> selectionObjects() {
        List<Object>s=smartSelection();
        if(!s.isEmpty())return new ArrayList<>(s);
        List<Object>one=new ArrayList<>();
        Object e=selectedObject();if(e!=null)one.add(e);return one;
    }

    private List<Object> selectedLines() {
        List<Object>out=new ArrayList<>();
        for(Object e:selectionObjects())if(isLine(e))out.add(e);
        return out;
    }

    private Object selectedObject() {
        try{return selectedField==null?null:selectedField.get(this);}catch(Exception e){return null;}
    }

    private void setSelection(Object... items) {
        try{
            List<Object>s=smartSelection();s.clear();Object first=null;
            for(Object e:items)if(e!=null){s.add(e);if(first==null)first=e;}
            if(selectedField!=null)selectedField.set(this,first);
            invalidate();dispatchWorkspaceState();
        }catch(Exception ignored){}
    }

    private void clearSelection(){setSelection();}

    private int sketchCount() {
        try{
            Field f=field(ParametricSketchCanvasView.class,"sketchSpaces");
            Object v=f.get(this);return v instanceof List?((List<?>)v).size():1;
        }catch(Exception e){return 1;}
    }

    private static boolean isLine(Object e){return e!=null&&"LineEntity".equals(e.getClass().getSimpleName());}
    private static boolean isCurve(Object e){if(e==null)return false;String n=e.getClass().getSimpleName();return "CircleEntity".equals(n)||"ArcEntity".equals(n);}

    private PointF endpoint(Object line,int i) {
        if(!isLine(line))return null;
        try{return i==0?new PointF(getFloat(line,"x1"),getFloat(line,"y1")):new PointF(getFloat(line,"x2"),getFloat(line,"y2"));}
        catch(Exception e){return null;}
    }

    private void setEndpoint(Object line,int i,float x,float y) {
        if(i==0){setFloat(line,"x1",x);setFloat(line,"y1",y);}
        else{setFloat(line,"x2",x);setFloat(line,"y2",y);}
    }

    private PointF midpoint(Object line) {
        PointF a=endpoint(line,0),b=endpoint(line,1);
        return a==null||b==null?null:new PointF((a.x+b.x)/2f,(a.y+b.y)/2f);
    }

    private int nearestEndpointIndex(Object line,PointF p) {
        PointF a=endpoint(line,0),b=endpoint(line,1);
        if(a==null||b==null)return 0;
        return dist(a.x,a.y,p.x,p.y)<=dist(b.x,b.y,p.x,p.y)?0:1;
    }

    private float lineLength(Object line) {
        PointF a=endpoint(line,0),b=endpoint(line,1);
        return a==null||b==null?0f:dist(a.x,a.y,b.x,b.y);
    }

    private int connectedPivotIndex(Object line) {
        PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return -1;
        for(Object o:entities())if(o!=line&&isLine(o)){
            PointF p0=endpoint(o,0),p1=endpoint(o,1);if(p0==null||p1==null)continue;
            if(Math.min(dist(a.x,a.y,p0.x,p0.y),dist(a.x,a.y,p1.x,p1.y))<0.12f)return 0;
            if(Math.min(dist(b.x,b.y,p0.x,p0.y),dist(b.x,b.y,p1.x,p1.y))<0.12f)return 1;
        }
        return -1;
    }

    private void setLineLength(Object line,float len,int pivot) {
        PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return;
        float old=dist(a.x,a.y,b.x,b.y);if(old<1e-6f)return;
        float ux=(b.x-a.x)/old,uy=(b.y-a.y)/old;
        if(pivot==0)setEndpoint(line,1,a.x+ux*len,a.y+uy*len);
        else if(pivot==1)setEndpoint(line,0,b.x-ux*len,b.y-uy*len);
        else{
            float cx=(a.x+b.x)/2f,cy=(a.y+b.y)/2f;
            setEndpoint(line,0,cx-ux*len/2f,cy-uy*len/2f);
            setEndpoint(line,1,cx+ux*len/2f,cy+uy*len/2f);
        }
    }

    private PointF center(Object e) {
        Object v=call(e,"center");
        return v instanceof PointF?new PointF((PointF)v):null;
    }

    private static PointF curveCenter(Object e) {
        try{return e==null?null:new PointF(getFloat(e,"x"),getFloat(e,"y"));}
        catch(Exception x){return null;}
    }

    private static PointF reflect(PointF p,PointF a,PointF b) {
        float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-8f)return new PointF(p.x,p.y);
        float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;
        float qx=a.x+t*dx,qy=a.y+t*dy;
        return new PointF(2*qx-p.x,2*qy-p.y);
    }

    private static PointF tangentPoint(PointF external,PointF center,float r,PointF preferred) {
        float dx=external.x-center.x,dy=external.y-center.y;
        float d=(float)Math.hypot(dx,dy);if(d<=r+1e-6f)return null;
        double theta=Math.atan2(dy,dx),alpha=Math.acos(r/d);
        PointF p1=new PointF(center.x+r*(float)Math.cos(theta+alpha),center.y+r*(float)Math.sin(theta+alpha));
        PointF p2=new PointF(center.x+r*(float)Math.cos(theta-alpha),center.y+r*(float)Math.sin(theta-alpha));
        return dist(preferred.x,preferred.y,p1.x,p1.y)<=dist(preferred.x,preferred.y,p2.x,p2.y)?p1:p2;
    }

    private float viewScale(){try{return viewScaleField==null?1f:viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private float offsetX(){try{return offsetXField==null?0f:offsetXField.getFloat(this);}catch(Exception e){return 0f;}}
    private float offsetY(){try{return offsetYField==null?0f:offsetYField.getFloat(this);}catch(Exception e){return 0f;}}
    private PointF worldToScreen(float x,float y){float s=PX_PER_MM*viewScale();return new PointF(offsetX()+x*s,offsetY()+y*s);}

    private Object call(Object target,String name) {
        if(target==null)return null;Class<?>c=target.getClass();
        while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}
        return null;
    }

    private static Field findField(Class<?>c,String name) {
        Class<?>x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;
    }
    private static float getFloat(Object o,String name)throws Exception{Field f=findField(o.getClass(),name);if(f==null)throw new NoSuchFieldException(name);return f.getFloat(o);}
    private static float safeGet(Object o,String name){try{return getFloat(o,name);}catch(Exception e){return 0f;}}
    private static void setFloat(Object o,String name,float v){try{Field f=findField(o.getClass(),name);if(f!=null)f.setFloat(o,v);}catch(Exception ignored){}}
    private static boolean containsIdentity(List<Object>list,Object v){for(Object e:list)if(e==v)return true;return false;}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
}
