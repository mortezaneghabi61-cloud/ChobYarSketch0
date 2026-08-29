package ir.chobyar.sketch;

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

/** Exact production contract for a straight-path Sweep. */
@RunWith(AndroidJUnit4.class)
public final class SweepCommandInstrumentationTest {
    private static final String TAG="SweepCommandContract";

    @Test
    public void rectangularProfileSweepsOneHundredMillimetersWithExactVolumeAndHistory(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);
                c.clearAll();

                c.executeCommand("RECT 0 0 20 10");
                CadCanvasView.Entity profile=c.selected;
                assertNotNull("Sweep profile was not created",profile);

                c.executeCommand("LINE 0 0 0 100");
                CadCanvasView.Entity path=c.selected;
                assertNotNull("Sweep path was not created",path);
                assertTrue("Sweep path must be a distinct entity",path!=profile);

                c.executeCommand("SWEEP3D 1 2");
                assertEquals("Sweep must create one Body",1,c.bodyCount());
                assertTrue("Sweep must switch to 3D overview",c.is3DOverview());

                SolidCSG swept=selectedCsg(c);
                Bounds b=bounds(swept);float[] e=sorted3(b.dx(),b.dy(),b.dz());
                near("Sweep section thickness",10f,e[0],.08f);
                near("Sweep section width",20f,e[1],.08f);
                near("Sweep path length",100f,e[2],.08f);
                double actual=volume(swept);
                near("Sweep exact volume",20000f,(float)actual,1.0f);
                assertEquals("Straight rectangular Sweep should have six faces",6,swept.polygons().size());

                c.rebuildHistory();
                assertEquals("Sweep rebuild must preserve one Body",1,c.bodyCount());
                SolidCSG replay=selectedCsg(c);
                near("Sweep volume after History rebuild",(float)actual,(float)volume(replay),.5f);
                assertEquals("Sweep face count changed after rebuild",swept.polygons().size(),replay.polygons().size());

                Log.i(TAG,"SWEEP3D_RESULT bodyCount=1 profile=20x10 path=100 extents=10x20x100 volume="
                        +fmt(actual)+" expected=20000.00 persistent=true faces="+replay.polygons().size());
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
