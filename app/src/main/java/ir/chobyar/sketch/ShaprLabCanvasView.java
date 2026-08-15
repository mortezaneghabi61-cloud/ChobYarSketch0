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
import java.util.Locale;

/**
 * Experimental/validation layer used to reproduce difficult direct-CAD sketch
 * workflows inside ChobYar itself. It intentionally builds on the real app
 * engine instead of using a mock screen, so every missing behavior found in the
 * lab becomes a real feature of the product.
 *
 * Added here:
 *  - Equal constraints for lines/circles/arcs
 *  - Symmetry constraint for two lines about an axis line
 *  - Midpoint endpoint-to-line constraint
 *  - Tangent constraint for a line and a circle/arc
 *  - a built-in Action Mount parametric sketch stress-test inspired by the
 *    official Shapr3D action-camera-mount tutorial (geometry is recreated with
 *    ChobYar primitives; no third-party visual assets are copied)
 */
public class ShaprLabCanvasView extends ParametricSketchCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float LAB_H = 46f;

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
    private int labPressed = 0;
    private boolean labBuilt = false;

    private final Paint labFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint relationText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprLabCanvasView(Context context) {
        super(context);
        initReflection();
        initPaints();
    }

    private void initReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            entitiesField = field(CadCanvasView.class, "entities");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void initPaints() {
        labFill.setColor(Color.argb(248, 245, 249, 255));
        labFill.setStyle(Paint.Style.FILL);
        labStroke.setColor(Color.rgb(70, 125, 220));
        labStroke.setStyle(Paint.Style.STROKE);
        labStroke.setStrokeWidth(2f);
        labText.setColor(Color.rgb(35, 80, 160));
        labText.setTextSize(20f);
        labText.setTextAlign(Paint.Align.CENTER);
        relationText.setColor(Color.rgb(35, 105, 205));
        relationText.setTextSize(20f);
        relationText.setTextAlign(Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------------
    // Public advanced constraints
    // ---------------------------------------------------------------------

    public String applyEqualConstraint() {
        List<Object> sel = selectionObjects();
        if (sel.size() < 2) return "برای Equal حداقل دو خط یا دو دایره/قوس را انتخاب کن";
        String type = simple(sel.get(0));
        boolean line = "LineEntity".equals(type);
        boolean curve = "CircleEntity".equals(type) || "ArcEntity".equals(type);
        if (!line && !curve) return "Equal برای خط، دایره و قوس فعال است";
        for (Object e : sel) {
            String t = simple(e);
            if (line && !"LineEntity".equals(t)) return "برای Equal همه انتخاب‌ها باید خط باشند";
            if (curve && !("CircleEntity".equals(t) || "ArcEntity".equals(t))) return "برای Equal همه انتخاب‌ها باید دایره/قوس باشند";
        }
        EqualRelation r = new EqualRelation(new ArrayList<>(sel), line);
        equalRelations.add(r);
        r.enforce();
        invalidate();
        return line ? "قید Equal: طول خطوط برابر شد" : "قید Equal: شعاع‌ها برابر شد";
    }

    /** Shapr workflow: choose two similar lines, then choose the symmetry axis last. */
    public String applySymmetryConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 3) return "برای Symmetry دو خط و سپس خط محور را انتخاب کن (۳ خط)";
        Object a = lines.get(0), b = lines.get(1), axis = lines.get(2);
        SymmetryRelation r = new SymmetryRelation(a, b, axis);
        symmetryRelations.add(r);
        r.enforce();
        invalidate();
        return "قید Symmetry اعمال شد؛ خط سوم محور تقارن است";
    }

    /** First selected line supplies the endpoint; second selected line is the host. */
    public String applyMidpointConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "برای Midpoint دو خط را انتخاب کن";
        Object endpointLine = lines.get(0), host = lines.get(1);
        PointF mid = midpoint(host);
        if (mid == null) return "Midpoint ممکن نشد";
        int endpointIndex = nearestEndpointIndex(endpointLine, mid);
        MidpointRelation r = new MidpointRelation(endpointLine, endpointIndex, host);
        midpointRelations.add(r);
        r.enforce();
        invalidate();
        return "قید Midpoint: سر خط اول به وسط خط دوم متصل شد";
    }

    public String applyTangentConstraint() {
        List<Object> sel = selectionObjects();
        if (sel.size() != 2) return "برای Tangent یک خط و یک دایره/قوس را انتخاب کن";
        Object line = null, curve = null;
        for (Object e : sel) {
            if (isLine(e)) line = e;
            else if (isCurve(e)) curve = e;
        }
        if (line == null || curve == null) return "برای Tangent یک خط و یک دایره/قوس لازم است";
        TangentRelation r = TangentRelation.create(line, curve);
        if (r == null) return "برای مماس، سر ثابت خط باید بیرون از شعاع دایره/قوس باشد";
        tangentRelations.add(r);
        r.enforce();
        invalidate();
        return "قید Tangent اعمال شد";
    }

    // ---------------------------------------------------------------------
    // Built-in difficult workflow / regression model
    // ---------------------------------------------------------------------

    /**
     * Builds a parametric 2D driving sketch based on the difficult sketch stage
     * of Shapr3D's action-camera-mount tutorial. Official tutorial values used
     * where explicit: 9 mm, 4.5 mm, 3 mm, 6 mm, 1.5 mm offset and 135 degrees.
     * ChobYar commands are centimeters, so those values are entered as cm.
     */
    public String buildActionMountLab() {
        if (labBuilt) return "تمرین Action Mount قبلاً ساخته شده؛ از Sketches می‌توانی آن را ببینی";

        createSketchSpace("LAB • Action Mount");

        // Driving skeleton. Values are centimeters at the UI boundary.
        Object center = make("LINE 0 -0.30 0 1.65");
        Object base = make("LINE -0.90 0 0.90 0");
        Object baseOffset = make("LINE -0.90 0.30 0.90 0.30");

        Object leftInner = make("LINE -0.90 0 -0.90 0.60");
        Object leftAngle = make("LINE -0.90 0.60 -1.20 0.90");
        Object leftOuter = make("LINE -1.20 0.90 -1.20 1.35");
        Object leftOffset = make("LINE -0.75 0 -0.75 0.60");

        Object rightInner = make("LINE 0.90 0 0.90 0.60");
        Object rightAngle = make("LINE 0.90 0.60 1.20 0.90");
        Object rightOuter = make("LINE 1.20 0.90 1.20 1.35");
        Object rightOffset = make("LINE 0.75 0 0.75 0.60");

        Object centerRib = make("LINE 0 0 0 0.60");
        Object leftHole = make("CIRCLE -1.50 1.35 0.30");
        Object rightHole = make("CIRCLE 1.50 1.35 0.30");

        // Exact directional and angle intent.
        setSelection(center); applyHorizontalVerticalConstraint();
        setSelection(base, baseOffset); applyHorizontalVerticalConstraint();
        setSelection(leftInner, leftOuter, leftOffset, rightInner, rightOuter, rightOffset, centerRib);
        applyHorizontalVerticalConstraint();

        setSelection(leftAngle); setSelectedLineAngle(135f);
        setSelection(rightAngle); setSelectedLineAngle(45f);

        // Symmetry and equal holes are persistent, not one-time transforms.
        setSelection(leftAngle, rightAngle, center); applySymmetryConstraint();
        setSelection(leftHole, rightHole); applyEqualConstraint();

        // Center rib is driven by the midpoint of the base.
        setSelection(centerRib, base); applyMidpointConstraint();

        // Outer verticals touch the circular bosses tangentially.
        setSelection(leftOuter, leftHole); applyTangentConstraint();
        setSelection(rightOuter, rightHole); applyTangentConstraint();

        // The centerline is a stable anchor: touching it must not destroy the layout.
        setSelection(center); toggleSelectedLock();

        // A second sketch mimics a later relief/projection sketch in the tutorial.
        createSketchSpace("LAB • Relief");
        Object reliefTop = make("LINE -0.40 1.70 0.40 1.70");
        Object reliefLeft = make("LINE -0.40 1.70 -0.70 2.00");
        Object reliefRight = make("LINE 0.40 1.70 0.70 2.00");
        Object reliefBottom = make("LINE -0.70 2.00 0.70 2.00");
        setSelection(reliefTop, reliefBottom); applyHorizontalVerticalConstraint();
        setSelection(reliefLeft); setSelectedLineAngle(135f);
        setSelection(reliefRight); setSelectedLineAngle(45f);
        setSelection(reliefLeft, reliefRight, center); // center remains a visible reference axis from first sketch
        applySymmetryConstraint();

        // Return to the driving sketch so the model opens where the tutorial starts.
        switchSketchSpace(Math.max(0, sketchCount() - 2));
        clearSelection();
        fitAll();
        labBuilt = true;
        invalidate();
        Toast.makeText(getContext(), "LAB ساخته شد: زاویه، Offset، تقارن، Equal، Midpoint، Tangent و Lock را تست کن", Toast.LENGTH_LONG).show();
        return "Action Mount LAB ساخته شد";
    }

    private Object make(String command) {
        executeCommand(command);
        return selectedObject();
    }

    // ---------------------------------------------------------------------
    // Relations / lightweight parametric solver
    // ---------------------------------------------------------------------

    private class EqualRelation {
        final List<Object> items;
        final boolean lineMode;
        final float value;
        EqualRelation(List<Object> items, boolean lineMode) {
            this.items = items; this.lineMode = lineMode;
            value = lineMode ? lineLength(items.get(0)) : Math.abs(safeGet(items.get(0), "r"));
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
        final Object a, b, axis;
        SymmetryRelation(Object a, Object b, Object axis) { this.a=a; this.b=b; this.axis=axis; }
        void enforce() {
            PointF a0=endpoint(a,0), a1=endpoint(a,1), q0=endpoint(axis,0), q1=endpoint(axis,1);
            if(a0==null||a1==null||q0==null||q1==null)return;
            PointF r0=reflect(a0,q0,q1), r1=reflect(a1,q0,q1);
            PointF b0=endpoint(b,0), b1=endpoint(b,1);
            if(b0==null||b1==null)return;
            float same=dist(b0.x,b0.y,r0.x,r0.y)+dist(b1.x,b1.y,r1.x,r1.y);
            float swap=dist(b0.x,b0.y,r1.x,r1.y)+dist(b1.x,b1.y,r0.x,r0.y);
            if(same<=swap){setEndpoint(b,0,r0.x,r0.y);setEndpoint(b,1,r1.x,r1.y);}
            else{setEndpoint(b,0,r1.x,r1.y);setEndpoint(b,1,r0.x,r0.y);}
        }
        boolean valid(List<Object> all){return containsIdentity(all,a)&&containsIdentity(all,b)&&containsIdentity(all,axis);}
    }

    private class MidpointRelation {
        final Object endpointLine, host; final int endpointIndex;
        MidpointRelation(Object endpointLine,int endpointIndex,Object host){this.endpointLine=endpointLine;this.endpointIndex=endpointIndex;this.host=host;}
        void enforce(){PointF m=midpoint(host);if(m!=null)setEndpoint(endpointLine,endpointIndex,m.x,m.y);}
        boolean valid(List<Object> all){return containsIdentity(all,endpointLine)&&containsIdentity(all,host);}
    }

    private class TangentRelation {
        final Object line, curve; final int movingIndex;
        TangentRelation(Object line,Object curve,int movingIndex){this.line=line;this.curve=curve;this.movingIndex=movingIndex;}
        static TangentRelation create(Object line,Object curve){
            if(line==null||curve==null)return null;
            PointF c=curveCenter(curve);float r=Math.abs(safeGet(curve,"r"));
            PointF p0=endpointStatic(line,0),p1=endpointStatic(line,1);
            if(c==null||p0==null||p1==null||r<=0)return null;
            float d0=dist(p0.x,p0.y,c.x,c.y),d1=dist(p1.x,p1.y,c.x,c.y);
            int moving=d0<=d1?0:1;
            PointF fixed=moving==0?p1:p0;
            if(dist(fixed.x,fixed.y,c.x,c.y)<=r+0.0001f)return null;
            return new TangentRelation(line,curve,moving);
        }
        void enforce(){
            PointF c=curveCenter(curve);float r=Math.abs(safeGet(curve,"r"));
            PointF current=endpoint(line,movingIndex),fixed=endpoint(line,1-movingIndex);
            if(c==null||current==null||fixed==null||r<=0)return;
            PointF t=tangentPoint(fixed,c,r,current);if(t!=null)setEndpoint(line,movingIndex,t.x,t.y);
        }
        boolean valid(List<Object> all){return containsIdentity(all,line)&&containsIdentity(all,curve);}
    }

    private void enforceLabRelations() {
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

    // ---------------------------------------------------------------------
    // Lab UI
    // ---------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        enforceLabRelations();
        super.onDraw(canvas);
        drawLabChip(canvas);
        drawRelationBadges(canvas);
    }

    private void drawLabChip(Canvas canvas) {
        float w=labBuilt?155f:190f;
        float right=getWidth()-12f;
        labChip.set(right-w,204f,right,204f+LAB_H);
        canvas.drawRoundRect(labChip,14f,14f,labFill);
        canvas.drawRoundRect(labChip,14f,14f,labStroke);
        canvas.drawText(labBuilt?"LAB • Action Mount":"LAB • تمرین سخت",labChip.centerX(),labChip.centerY()+7f,labText);
    }

    private void drawRelationBadges(Canvas canvas) {
        for(EqualRelation r:equalRelations)for(Object e:r.items)drawBadge(canvas,e,"=");
        for(SymmetryRelation r:symmetryRelations){drawBadge(canvas,r.a,"S");drawBadge(canvas,r.b,"S");}
        for(MidpointRelation r:midpointRelations)drawBadge(canvas,r.endpointLine,"M");
        for(TangentRelation r:tangentRelations)drawBadge(canvas,r.line,"T");
    }

    private void drawBadge(Canvas canvas,Object e,String text){
        PointF c=center(e);if(c==null)return;PointF s=worldToScreen(c.x,c.y);
        canvas.drawText(text,s.x+18f,s.y-18f,relationText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int a=event.getActionMasked();
        float x=event.getX(),y=event.getY();
        if(a==MotionEvent.ACTION_DOWN&&labChip.contains(x,y)){labPressed=1;return true;}
        if(labPressed!=0){
            if(a==MotionEvent.ACTION_UP){labPressed=0;if(labChip.contains(x,y))showLabMenu();}
            else if(a==MotionEvent.ACTION_CANCEL)labPressed=0;
            return true;
        }
        boolean handled=super.onTouchEvent(event);
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){enforceLabRelations();invalidate();}
        return handled;
    }

    private void showLabMenu() {
        String[] items={
                labBuilt?"✓ Action Mount LAB ساخته شده":"ساخت Action Mount LAB",
                "= Equal / برابری",
                "S Symmetry / تقارن (دو خط + محور)",
                "M Midpoint / اتصال به وسط خط",
                "T Tangent / مماس خط و دایره/قوس",
                "Fit All"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("ChobYar • Shapr LAB")
                .setMessage("این محیط برای بازسازی تمرین‌های سخت CAD و پیدا کردن کمبودهای واقعی ابزارهاست.")
                .setItems(items,(d,w)->{
                    String result;
                    if(w==0)result=buildActionMountLab();
                    else if(w==1)result=applyEqualConstraint();
                    else if(w==2)result=applySymmetryConstraint();
                    else if(w==3)result=applyMidpointConstraint();
                    else if(w==4)result=applyTangentConstraint();
                    else{fitAll();result="Fit All";}
                    if(result!=null&&!result.isEmpty())Toast.makeText(getContext(),result,Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("بستن",null).show();
    }

    @Override
    public void clearAll() {
        super.clearAll();
        equalRelations.clear();symmetryRelations.clear();midpointRelations.clear();tangentRelations.clear();
        labBuilt=false;
    }

    @Override
    public void undo() {
        super.undo();
        // Undo clones base entities, so identity-based lab relations are rebuilt
        // only when explicitly re-applied; stale references must never survive.
        equalRelations.clear();symmetryRelations.clear();midpointRelations.clear();tangentRelations.clear();
        labBuilt=false;
    }

    // ---------------------------------------------------------------------
    // Reflection / geometry helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities(){try{return entitiesField==null?new ArrayList<>(): (List<Object>)entitiesField.get(this);}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked")
    private List<Object> smartSelection(){try{return selectedObjectsField==null?new ArrayList<>(): (List<Object>)selectedObjectsField.get(this);}catch(Exception e){return new ArrayList<>();}}
    private List<Object> selectionObjects(){List<Object>s=smartSelection();if(!s.isEmpty())return new ArrayList<>(s);List<Object>o=new ArrayList<>();Object e=selectedObject();if(e!=null)o.add(e);return o;}
    private List<Object> selectedLines(){List<Object>o=new ArrayList<>();for(Object e:selectionObjects())if(isLine(e))o.add(e);return o;}
    private Object selectedObject(){try{return selectedField==null?null:selectedField.get(this);}catch(Exception e){return null;}}
    private void setSelection(Object...items){try{List<Object>s=smartSelection();s.clear();Object first=null;for(Object e:items)if(e!=null){s.add(e);if(first==null)first=e;}if(selectedField!=null)selectedField.set(this,first);invalidate();dispatchWorkspaceState();}catch(Exception ignored){}}
    private void clearSelection(){setSelection();}
    private int sketchCount(){try{Field f=field(ParametricSketchCanvasView.class,"sketchSpaces");Object v=f.get(this);return v instanceof List?((List<?>)v).size():1;}catch(Exception e){return 1;}}

    private boolean isLine(Object e){return e!=null&&"LineEntity".equals(simple(e));}
    private boolean isCurve(Object e){String s=simple(e);return "CircleEntity".equals(s)||"ArcEntity".equals(s);}
    private static String simple(Object e){return e==null?"":e.getClass().getSimpleName();}

    private PointF endpoint(Object line,int i){return endpointStatic(line,i);}
    private static PointF endpointStatic(Object line,int i){if(line==null||!"LineEntity".equals(simple(line)))return null;try{return i==0?new PointF(getFloat(line,"x1"),getFloat(line,"y1")):new PointF(getFloat(line,"x2"),getFloat(line,"y2"));}catch(Exception e){return null;}}
    private void setEndpoint(Object line,int i,float x,float y){if(i==0){setFloat(line,"x1",x);setFloat(line,"y1",y);}else{setFloat(line,"x2",x);setFloat(line,"y2",y);}}
    private PointF midpoint(Object line){PointF a=endpoint(line,0),b=endpoint(line,1);return a==null||b==null?null:new PointF((a.x+b.x)/2f,(a.y+b.y)/2f);}
    private int nearestEndpointIndex(Object line,PointF p){PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return 0;return dist(a.x,a.y,p.x,p.y)<=dist(b.x,b.y,p.x,p.y)?0:1;}
    private float lineLength(Object line){PointF a=endpoint(line,0),b=endpoint(line,1);return a==null||b==null?0f:dist(a.x,a.y,b.x,b.y);}

    private int connectedPivotIndex(Object line){
        PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return -1;
        for(Object other:entities())if(other!=line&&isLine(other)){
            PointF o0=endpoint(other,0),o1=endpoint(other,1);
            if(o0==null||o1==null)continue;
            if(Math.min(dist(a.x,a.y,o0.x,o0.y),dist(a.x,a.y,o1.x,o1.y))<0.12f)return 0;
            if(Math.min(dist(b.x,b.y,o0.x,o0.y),dist(b.x,b.y,o1.x,o1.y))<0.12f)return 1;
        }
        return -1;
    }

    private void setLineLength(Object line,float len,int pivot){
        PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return;
        float old=dist(a.x,a.y,b.x,b.y);if(old<1e-6f)return;float ux=(b.x-a.x)/old,uy=(b.y-a.y)/old;
        if(pivot==0)setEndpoint(line,1,a.x+ux*len,a.y+uy*len);
        else if(pivot==1)setEndpoint(line,0,b.x-ux*len,b.y-uy*len);
        else{float cx=(a.x+b.x)/2f,cy=(a.y+b.y)/2f;setEndpoint(line,0,cx-ux*len/2f,cy-uy*len/2f);setEndpoint(line,1,cx+ux*len/2f,cy+uy*len/2f);}
    }

    private PointF center(Object e){Object v=call(e,"center");return v instanceof PointF?new PointF((PointF)v):null;}
    private static PointF curveCenter(Object e){if(e==null)return null;try{return new PointF(getFloat(e,"x"),getFloat(e,"y"));}catch(Exception x){return null;}}

    private static PointF reflect(PointF p,PointF a,PointF b){
        float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-8f)return new PointF(p.x,p.y);
        float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;float qx=a.x+t*dx,qy=a.y+t*dy;
        return new PointF(2f*qx-p.x,2f*qy-p.y);
    }

    private static PointF tangentPoint(PointF external,PointF center,float r,PointF preferred){
        float dx=external.x-center.x,dy=external.y-center.y;float d=(float)Math.hypot(dx,dy);if(d<=r+1e-6f)return null;
        double theta=Math.atan2(dy,dx),alpha=Math.acos(r/d);
        PointF p1=new PointF(center.x+r*(float)Math.cos(theta+alpha),center.y+r*(float)Math.sin(theta+alpha));
        PointF p2=new PointF(center.x+r*(float)Math.cos(theta-alpha),center.y+r*(float)Math.sin(theta-alpha));
        return dist(preferred.x,preferred.y,p1.x,p1.y)<=dist(preferred.x,preferred.y,p2.x,p2.y)?p1:p2;
    }

    private float viewScale(){try{return viewScaleField==null?1f:viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private float offsetX(){try{return offsetXField==null?0f:offsetXField.getFloat(this);}catch(Exception e){return 0f;}}
    private float offsetY(){try{return offsetYField==null?0f:offsetYField.getFloat(this);}catch(Exception e){return 0f;}}
    private PointF worldToScreen(float x,float y){float s=PX_PER_MM*viewScale();return new PointF(offsetX()+x*s,offsetY()+y*s);}

    private Object call(Object target,String name){if(target==null)return null;Class<?>c=target.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}return null;}
    private static Field findField(Class<?>c,String name){Class<?>x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float getFloat(Object o,String name)throws Exception{Field f=findField(o.getClass(),name);if(f==null)throw new NoSuchFieldException(name);return f.getFloat(o);}
    private static float safeGet(Object o,String name){try{return getFloat(o,name);}catch(Exception e){return 0f;}}
    private static void setFloat(Object o,String name,float v){try{Field f=findField(o.getClass(),name);if(f!=null)f.setFloat(o,v);}catch(Exception ignored){}}
    private static boolean containsIdentity(List<Object>list,Object v){for(Object e:list)if(e==v)return true;return false;}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
}
