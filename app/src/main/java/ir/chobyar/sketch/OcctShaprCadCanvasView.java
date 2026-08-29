package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shapr-inspired Sketch workspace on top of the OCCT exact-modeling path.
 * Original UI/symbols are used; only the CAD workflow is mirrored.
 */
public class OcctShaprCadCanvasView extends OcctMeasureCadCanvasView {
    private static final int NONE=0, ELLIPSE=1, SPLINE=2;
    private static final float PX_PER_MM=3f;

    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Method saveUndoMethod;
    private Constructor<?> polylineCtor;

    private int custom=NONE;
    private PointF start;
    private final List<PointF> stroke=new ArrayList<>();
    private float lastSX,lastSY;

    public OcctShaprCadCanvasView(Context c){super(c);initReflection();}

    private void initReflection(){
        try{
            entitiesField=field(CadCanvasView.class,"entities");
            selectedField=field(CadCanvasView.class,"selected");
            selectedObjectsField=field(SmartCadCanvasView.class,"selectedObjects");
            viewScaleField=field(CadCanvasView.class,"viewScale");
            offsetXField=field(CadCanvasView.class,"offsetX");
            offsetYField=field(CadCanvasView.class,"offsetY");
            saveUndoMethod=CadCanvasView.class.getDeclaredMethod("saveUndo");saveUndoMethod.setAccessible(true);
            Class<?> p=Class.forName("ir.chobyar.sketch.CadCanvasView$PolylineEntity");
            polylineCtor=p.getDeclaredConstructor(List.class,boolean.class);polylineCtor.setAccessible(true);
        }catch(Exception ignored){}
    }
    private static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}

    public void showShaprSketchMenu(){
        String[] items={
                "⌁ Automatic Line / Arc","╱ Line","⌒ Arc","〰 Spline","▭ Rectangle","○ Circle","⬭ Ellipse","⬡ Polygon",
                "⧉ Offset Edge","↗ Move / Rotate Sketch","⠿ Pattern Sketch","⌫ Trim","⎘ Project Sketch","⌖ Measure",
                "⌁ Constraints","🔒 Lock / Unlock","┄ Make Construction","⌫ Delete"};
        new AlertDialog.Builder(getContext()).setTitle("Sketch").setItems(items,(d,w)->{
            if(w==0)activate(TOOL_LINE,"Automatic drawing activated");
            else if(w==1)activate(TOOL_LINE,"Line activated");
            else if(w==2)activate(TOOL_ARC,"Arc activated");
            else if(w==3)startSpline();
            else if(w==4)activate(TOOL_RECT,"Rectangle activated");
            else if(w==5)activate(TOOL_CIRCLE,"Circle activated");
            else if(w==6)startEllipse();
            else if(w==7)activate(TOOL_POLYGON,"Polygon activated");
            else if(w==8)offsetDialog();
            else if(w==9)transformDialog();
            else if(w==10)patternMenu();
            else if(w==11)msg(trimSelectedLines());
            else if(w==12)msg(projectReference());
            else if(w==13)showSketchMeasureInspector();
            else if(w==14)showSmartConstraintMenu();
            else if(w==15)msg(toggleSelectedLock());
            else if(w==16)msg(toggleConstruction());
            else {deleteSelected();dispatchWorkspaceState();}
        }).setNegativeButton("Close",null).show();
    }

    public void showShaprModelingToolsMenu(){
        String[] items={
                "⬆ Extrude",
                "⟳ Revolve",
                "➜ Sweep",
                "≋ Loft",
                "∪ Union",
                "− Subtract",
                "∩ Intersect",
                "⌒ Fillet",
                "◩ Chamfer",
                "▱ Shell",
                "↕ Offset Face / Push-Pull",
                "✥ Move / Rotate • Direct Edit",
                "⏱ Design History"
        };
        new AlertDialog.Builder(getContext()).setTitle("Tools • 3D").setItems(items,(d,w)->{
            if(w==0)showInteractiveExtrude();
            else if(w==1)showRevolveTool();
            else if(w==2)showSweepTool();
            else if(w==3)showLoftTool();
            else if(w==4)startBooleanTool("UNION");
            else if(w==5)startBooleanTool("SUBTRACT");
            else if(w==6)startBooleanTool("INTERSECT");
            else if(w==7)showSelectedFillet();
            else if(w==8)showSelectedChamfer();
            else if(w==9)showSelectedShell();
            else if(w==10)showSelectedPushPull();
            else if(w==11)showDirectManager();
            else showHistoryManager();
        }).setNegativeButton("Close",null).show();
    }

    private void activate(int t,String s){custom=NONE;stroke.clear();super.setTool(t);msg(s);}
    private void startEllipse(){super.setTool(TOOL_SELECT);custom=ELLIPSE;start=null;msg("Ellipse: text Center touch text text text X/Y text");}
    private void startSpline(){super.setTool(TOOL_SELECT);custom=SPLINE;stroke.clear();msg("Spline: text text text text text text text text text");}

    @Override public boolean onTouchEvent(MotionEvent e){
        if(custom==NONE)return super.onTouchEvent(e);
        if(e.getPointerCount()>1)return super.onTouchEvent(e);
        int a=e.getActionMasked();
        if(custom==ELLIPSE){
            if(a==MotionEvent.ACTION_DOWN){start=world(e.getX(),e.getY());return true;}
            if(a==MotionEvent.ACTION_UP&&start!=null){
                PointF q=world(e.getX(),e.getY());float rx=Math.abs(q.x-start.x),ry=Math.abs(q.y-start.y);
                if(rx<.5f||ry<.5f){msg("Ellipse text text text");return true;}
                List<PointF> p=new ArrayList<>();
                for(int i=0;i<96;i++){double t=2*Math.PI*i/96d;p.add(new PointF(start.x+(float)Math.cos(t)*rx,start.y+(float)Math.sin(t)*ry));}
                Object x=addPolyline(p,true);custom=NONE;start=null;if(x!=null)msg("Ellipse created");dispatchWorkspaceState();return true;
            }
            if(a==MotionEvent.ACTION_CANCEL){custom=NONE;start=null;return true;}
            return true;
        }
        if(custom==SPLINE){
            if(a==MotionEvent.ACTION_DOWN){stroke.clear();stroke.add(world(e.getX(),e.getY()));lastSX=e.getX();lastSY=e.getY();return true;}
            if(a==MotionEvent.ACTION_MOVE){float dx=e.getX()-lastSX,dy=e.getY()-lastSY;if(dx*dx+dy*dy>=36f){stroke.add(world(e.getX(),e.getY()));lastSX=e.getX();lastSY=e.getY();}return true;}
            if(a==MotionEvent.ACTION_UP){stroke.add(world(e.getX(),e.getY()));if(stroke.size()<3){msg("text Spline text text");stroke.clear();return true;}Object x=addPolyline(smooth(stroke,5),false);custom=NONE;stroke.clear();if(x!=null)msg("Spline text created");dispatchWorkspaceState();return true;}
            if(a==MotionEvent.ACTION_CANCEL){custom=NONE;stroke.clear();return true;}
        }
        return true;
    }

    private static List<PointF> smooth(List<PointF> s,int div){
        List<PointF> o=new ArrayList<>();
        for(int i=0;i<s.size()-1;i++){
            PointF p0=s.get(Math.max(0,i-1)),p1=s.get(i),p2=s.get(i+1),p3=s.get(Math.min(s.size()-1,i+2));
            for(int j=0;j<div;j++){float t=j/(float)div,t2=t*t,t3=t2*t;
                float x=.5f*((2*p1.x)+(-p0.x+p2.x)*t+(2*p0.x-5*p1.x+4*p2.x-p3.x)*t2+(-p0.x+3*p1.x-3*p2.x+p3.x)*t3);
                float y=.5f*((2*p1.y)+(-p0.y+p2.y)*t+(2*p0.y-5*p1.y+4*p2.y-p3.y)*t2+(-p0.y+3*p1.y-3*p2.y+p3.y)*t3);o.add(new PointF(x,y));}
        }
        PointF z=s.get(s.size()-1);o.add(new PointF(z.x,z.y));return o;
    }

    private void offsetDialog(){
        EditText v=input("10mm");
        new AlertDialog.Builder(getContext()).setTitle("Offset Edge • mm").setView(v).setPositiveButton("Offset",(d,w)->{
            try{msg(offsetSelected(lengthMm(v.getText().toString())));}catch(Exception ex){msg("Distance is invalid");}
        }).setNegativeButton("Cancel",null).show();
    }

    /** Selection-adaptive entry used by the production workspace. */
    public void showOffsetEdgeTool(){offsetDialog();}

    private void transformDialog(){
        LinearLayout b=new LinearLayout(getContext());b.setOrientation(LinearLayout.VERTICAL);
        EditText x=input("0mm"),y=input("0mm"),r=input("0");x.setHint("Move X");y.setHint("Move Y");r.setHint("Rotate °");b.addView(x);b.addView(y);b.addView(r);
        new AlertDialog.Builder(getContext()).setTitle("Move / Rotate Sketch").setView(b).setPositiveButton("Apply",(d,w)->{
            try{float dx=lengthMm(x.getText().toString()),dy=lengthMm(y.getText().toString()),deg=Float.parseFloat(digits(r.getText().toString()));
                if(Math.abs(dx)>1e-6||Math.abs(dy)>1e-6)moveSelected(dx,dy);if(Math.abs(deg)>1e-6)msg(rotateSelected(deg));dispatchWorkspaceState();
            }catch(Exception ex){msg("text is invalid");}
        }).setNegativeButton("Cancel",null).show();
    }

    private void patternMenu(){String[] a={"↔ Linear Pattern","⟳ Circular Pattern"};new AlertDialog.Builder(getContext()).setTitle("Pattern Sketch").setItems(a,(d,w)->{if(w==0)linearPattern();else circularPatternDialog();}).show();}
    private void linearPattern(){
        LinearLayout b=new LinearLayout(getContext());b.setOrientation(LinearLayout.VERTICAL);EditText n=input("4"),x=input("20mm"),y=input("0mm");n.setHint("Count");x.setHint("Spacing X");y.setHint("Spacing Y");b.addView(n);b.addView(x);b.addView(y);
        new AlertDialog.Builder(getContext()).setTitle("Linear Pattern").setView(b).setPositiveButton("Create",(d,w)->{try{msg(arraySelected(Integer.parseInt(digits(n.getText().toString())),lengthMm(x.getText().toString()),lengthMm(y.getText().toString())));}catch(Exception ex){msg("Pattern is invalid");}}).setNegativeButton("Cancel",null).show();
    }
    private void circularPatternDialog(){
        LinearLayout b=new LinearLayout(getContext());b.setOrientation(LinearLayout.VERTICAL);EditText n=input("6"),cx=input("0mm"),cy=input("0mm"),ang=input("360");n.setHint("Count");cx.setHint("Center X");cy.setHint("Center Y");ang.setHint("Total angle °");b.addView(n);b.addView(cx);b.addView(cy);b.addView(ang);
        new AlertDialog.Builder(getContext()).setTitle("Circular Pattern").setView(b).setPositiveButton("Create",(d,w)->{try{msg(circularPattern(Integer.parseInt(digits(n.getText().toString())),lengthMm(cx.getText().toString()),lengthMm(cy.getText().toString()),Float.parseFloat(digits(ang.getText().toString()))));}catch(Exception ex){msg("Circular Pattern is invalid");}}).setNegativeButton("Cancel",null).show();
    }

    private String circularPattern(int count,float cx,float cy,float total){
        List<Object> s=selection();if(s.isEmpty())return"Select geometry first";if(count<2||count>200)return"Count text text 2 text 200 text";
        try{saveUndo();List<Object> seed=new ArrayList<>(s);Object last=null;float step=total/count;
            for(int i=1;i<count;i++)for(Object e:seed){Object c=call(e,"copy");if(c==null)continue;call(c,"rotate",new Class<?>[]{float.class,float.class,float.class},cx,cy,step*i);entities().add(c);last=c;}
            if(last!=null)selectOne(last);invalidate();dispatchWorkspaceState();return"Circular Pattern: "+count+" text";
        }catch(Exception ex){return"Circular Pattern text text";}
    }

    private String projectReference(){
        List<Object> s=selection();if(s.isEmpty())return"First Sketch text Edge text Selection text";
        try{saveUndo();Object last=null;for(Object e:s){Object c=call(e,"copy");if(c==null)continue;setConstruction(c,true);entities().add(c);last=c;}if(last!=null)selectOne(last);invalidate();dispatchWorkspaceState();return"Projection text created";}catch(Exception ex){return"Project text text";}
    }

    private String toggleConstruction(){
        List<Object> s=selection();if(s.isEmpty())return"Select a sketch first";
        try{boolean make=false;for(Object e:s){Field f=findField(e.getClass(),"construction");if(f==null||!f.getBoolean(e)){make=true;break;}}for(Object e:s)setConstruction(e,make);invalidate();dispatchWorkspaceState();return make?"Construction On text":"Construction Off text";}catch(Exception ex){return"Construction is unavailable";}
    }
    private void setConstruction(Object e,boolean v)throws Exception{Field f=findField(e.getClass(),"construction");if(f!=null){f.setBoolean(e,v);return;}Method m=findMethod(e.getClass(),"setConstruction",boolean.class);if(m!=null)m.invoke(e,v);}

    private Object addPolyline(List<PointF> p,boolean closed){
        try{if(polylineCtor==null||p.size()<2)return null;saveUndo();Object e=polylineCtor.newInstance(p,closed);Method m=findMethod(e.getClass(),"setLayer",String.class);if(m!=null)m.invoke(e,getCurrentLayer());entities().add(e);selectOne(e);invalidate();return e;}catch(Exception ex){msg("Create Geometry Done text");return null;}
    }

    @SuppressWarnings("unchecked") private List<Object> entities()throws Exception{return(List<Object>)entitiesField.get(this);}
    @SuppressWarnings("unchecked") private List<Object> selection(){try{Object m=selectedObjectsField.get(this);if(m instanceof List&&!((List<?>)m).isEmpty())return new ArrayList<>((List<Object>)m);Object e=selectedField.get(this);List<Object> o=new ArrayList<>();if(e!=null)o.add(e);return o;}catch(Exception ex){return new ArrayList<>();}}
    @SuppressWarnings("unchecked") private void selectOne(Object e)throws Exception{selectedField.set(this,e);Object m=selectedObjectsField.get(this);if(m instanceof List){((List<Object>)m).clear();((List<Object>)m).add(e);}}
    private void saveUndo()throws Exception{if(saveUndoMethod!=null)saveUndoMethod.invoke(this);}
    private PointF world(float sx,float sy){try{float k=PX_PER_MM*viewScaleField.getFloat(this);return new PointF((sx-offsetXField.getFloat(this))/k,(sy-offsetYField.getFloat(this))/k);}catch(Exception ex){return new PointF(sx/PX_PER_MM,sy/PX_PER_MM);}}

    private EditText input(String text){EditText e=new EditText(getContext());e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setText(text);e.setSelectAllOnFocus(true);return e;}
    private static float lengthMm(String raw){String s=digits(raw).toLowerCase(Locale.US).trim().replace(',','.');boolean cm=s.endsWith("cm")||s.endsWith("cm")||s.endsWith("cm");s=s.replace("mm","").replace("mm","").replace("cm","").replace("cm","").replace("mm","").replace("cm","").trim();float v=Float.parseFloat(s);return cm?v*10f:v;}
    private static String digits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);b.append(c);}return b.toString().trim();}
    private static Field findField(Class<?> c,String n){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private static Method findMethod(Class<?> c,String n,Class<?>...t){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(n,t);m.setAccessible(true);return m;}catch(Exception ignored){}return null;}
    private static Object call(Object o,String n)throws Exception{Method m=findMethod(o.getClass(),n);return m==null?null:m.invoke(o);}
    private static Object call(Object o,String n,Class<?>[]t,Object...a)throws Exception{Method m=findMethod(o.getClass(),n,t);return m==null?null:m.invoke(o,a);}
    private void msg(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}
