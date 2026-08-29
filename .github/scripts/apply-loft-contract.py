from pathlib import Path

SPATIAL = Path('app/src/main/java/ir/chobyar/sketch/SpatialCadCanvasView.java')
ADVANCED = Path('app/src/main/java/ir/chobyar/sketch/AdvancedParametricSolidCadCanvasView.java')
TEST = Path('app/src/androidTest/java/ir/chobyar/sketch/LoftCommandInstrumentationTest.java')

spatial = SPATIAL.read_text(encoding='utf-8')
spatial_anchor = '''    private void showOffsetPlaneDialog() {\n'''
spatial_block = r'''    /** Deterministic non-modal parallel Sketch plane entry for commands/tests. */
    public String createOffsetSketchSpace(float offsetMm, String requestedName) {
        if (!Float.isFinite(offsetMm)) return "Plane distance is invalid";
        Geometry3D.Plane3D base = activePlane == null ? Geometry3D.xy() : activePlane;
        String label = base.label + " + " + fmt(offsetMm) + " mm";
        pendingPlane = base.offset(offsetMm, label);
        String name = requestedName == null || requestedName.trim().isEmpty()
                ? "Offset Plane " + (planeByLayer.size() + 1)
                : requestedName.trim();
        String result = createSketchSpace(name);
        overview3D = false;
        orbiting = false;
        navigating2D = false;
        invalidate();
        return result;
    }

'''
if 'createOffsetSketchSpace(float offsetMm' not in spatial:
    if spatial_anchor not in spatial:
        raise SystemExit('Spatial offset-plane anchor not found')
    spatial = spatial.replace(spatial_anchor, spatial_block + spatial_anchor, 1)
    SPATIAL.write_text(spatial, encoding='utf-8')

advanced = ADVANCED.read_text(encoding='utf-8')
loft_anchor = '''    private void startLoft(){\n'''
loft_block = r'''    /** Deterministic non-modal Loft entry using 1-based sketch entity numbers. */
    public String createLoftByEntityIndex(int firstNumber,int secondNumber){
        List<Object> all=entities();
        if(firstNumber<1||secondNumber<1||firstNumber>all.size()||secondNumber>all.size())
            return "Entity number must be between 1 and "+all.size();
        if(firstNumber==secondNumber)return "Loft profiles must be different";
        return createLoft(all.get(firstNumber-1),all.get(secondNumber-1));
    }

'''
if 'createLoftByEntityIndex(' not in advanced:
    if loft_anchor not in advanced:
        raise SystemExit('Loft anchor not found')
    advanced = advanced.replace(loft_anchor, loft_block + loft_anchor, 1)

if '"LOFT3D".equalsIgnoreCase(a[0])' not in advanced:
    sweep_block = r'''                if("SWEEP3D".equalsIgnoreCase(a[0])){
                    if(a.length!=3)return "SWEEP3D — profile number and path are required; example: SWEEP3D 1 2";
                    try{return createSweepByEntityIndex(Integer.parseInt(a[1]),Integer.parseInt(a[2]));}
                    catch(NumberFormatException e){return "Entity number must be an integer";}
                }
'''
    loft_command = r'''                if("LOFT3D".equalsIgnoreCase(a[0])){
                    if(a.length!=3)return "LOFT3D — two profile numbers are required; example: LOFT3D 1 2";
                    try{return createLoftByEntityIndex(Integer.parseInt(a[1]),Integer.parseInt(a[2]));}
                    catch(NumberFormatException e){return "Entity number must be an integer";}
                }
'''
    if sweep_block not in advanced:
        raise SystemExit('SWEEP3D command block not found for Loft insertion')
    advanced = advanced.replace(sweep_block, sweep_block + loft_command, 1)

ADVANCED.write_text(advanced, encoding='utf-8')

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(r'''package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exact production contract for Loft between two similar profiles on parallel planes. */
@RunWith(AndroidJUnit4.class)
public final class LoftCommandInstrumentationTest {
    private static final String TAG="LoftCommandContract";

    @Test
    public void similarRectanglesLoftAcrossSixtyMillimetersWithExactVolumeAndHistory(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);
                c.clearAll();

                String lower=c.executeCommand("RECT 0 0 20 10");
                assertTrue("Lower Loft RECT rejected: "+lower,lower.contains("Rectangle created"));

                String plane=c.createOffsetSketchSpace(60f,"Loft Upper");
                assertTrue("Offset Sketch plane was not created: "+plane,plane.contains("Loft Upper")||plane.contains("Sketch"));
                assertTrue("Offset plane label must expose 60 mm: "+c.activePlaneLabel(),c.activePlaneLabel().contains("60"));

                String upper=c.executeCommand("RECT 0 0 40 20");
                assertTrue("Upper Loft RECT rejected: "+upper,upper.contains("Rectangle created"));

                String result=c.executeCommand("LOFT3D 1 2");
                assertTrue("LOFT3D rejected: "+result,result.contains("Loft created"));
                assertEquals("Loft must create one Body",1,c.bodyCount());
                assertTrue("Loft must switch to 3D overview",c.is3DOverview());

                SolidCSG loft=selectedCsg(c);
                Bounds b=bounds(loft);float[] e=sorted3(b.dx(),b.dy(),b.dz());
                near("Loft small section",20f,e[0],.08f);
                near("Loft large section",40f,e[1],.08f);
                near("Loft plane spacing",60f,e[2],.08f);

                double actual=volume(loft);
                // Similar rectangles: V=h/3*(A1+sqrt(A1*A2)+A2)
                // h=60, A1=200, A2=800 => 28000 mm^3.
                near("Loft frustum volume",28000f,(float)actual,2.0f);
                assertEquals("64-sample Loft should have 64 side faces plus two caps",66,loft.polygons().size());

                String rebuilt=c.rebuildHistory();
                assertTrue("Loft History rebuild failed: "+rebuilt,
                        rebuilt.contains("Form 1")&&!rebuilt.toLowerCase(java.util.Locale.US).contains("error"));
                SolidCSG replay=selectedCsg(c);
                near("Loft volume after History rebuild",(float)actual,(float)volume(replay),.5f);
                assertEquals("Loft face count changed after rebuild",loft.polygons().size(),replay.polygons().size());

                Log.i(TAG,"LOFT3D_RESULT bodyCount=1 lower=20x10 upper=40x20 spacing=60 extents=20x40x60 volume="
                        +fmt(actual)+" expected=28000.00 persistent=true faces="+replay.polygons().size());
            });
        }
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){
        Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());
        assertNotNull("Production Shapr3DGuide canvas not found",c);return c;
    }
    private static Shapr3DGuideCadCanvasView find(View v){
        if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}
        return null;
    }
    private static SolidCSG selectedCsg(Shapr3DGuideCadCanvasView c){
        try{Field sf=SolidCadCanvasView.class.getDeclaredField("selectedBody");sf.setAccessible(true);Object body=sf.get(c);assertNotNull("No selected Body",body);Field cf=body.getClass().getDeclaredField("csg");cf.setAccessible(true);SolidCSG x=(SolidCSG)cf.get(body);assertNotNull(x);return x;}
        catch(Exception e){throw new AssertionError("Cannot inspect selected Body",e);}
    }
    private static double volume(SolidCSG csg){
        double sixV=0d;for(SolidCSG.Polygon p:csg.polygons()){if(p.vertices.size()<3)continue;Geometry3D.Vec3 a=p.vertices.get(0).pos;for(int i=1;i<p.vertices.size()-1;i++){Geometry3D.Vec3 b=p.vertices.get(i).pos,d=p.vertices.get(i+1).pos;sixV+=a.dot(b.cross(d));}}return Math.abs(sixV/6d);
    }
    private static Bounds bounds(SolidCSG csg){Bounds b=new Bounds();for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices)b.add(v.pos.x,v.pos.y,v.pos.z);assertTrue("Body bounds empty",b.seen);return b;}
    private static float[] sorted3(float a,float b,float c){float[] x={Math.abs(a),Math.abs(b),Math.abs(c)};java.util.Arrays.sort(x);return x;}
    private static void near(String label,float expected,float actual,float eps){assertTrue(label+" expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);}
    private static String fmt(double v){return String.format(java.util.Locale.US,"%.2f",v);}
    private static final class Bounds{boolean seen;float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;void add(float x,float y,float z){seen=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}float dx(){return maxX-minX;}float dy(){return maxY-minY;}float dz(){return maxZ-minZ;}}
}
''', encoding='utf-8')