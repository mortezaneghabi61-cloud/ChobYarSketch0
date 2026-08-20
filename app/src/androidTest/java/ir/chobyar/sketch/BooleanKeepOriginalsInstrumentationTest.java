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

/** Shapr3D Manual 26.100 Boolean Keep Originals contracts on the production canvas. */
@RunWith(AndroidJUnit4.class)
public final class BooleanKeepOriginalsInstrumentationTest {
    private static final String TAG="Manual26100Boolean";
    private static final float EPS=.08f;

    @Test
    public void unionKeepOriginalsPreservesBothInputsAcrossHistory(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
                String result=c.executeCommand("UNION 1 2 KEEP");
                assertTrue("UNION KEEP rejected: "+result,result.contains("Keep Originals"));
                assertEquals("Keep Originals must retain target + tool + result",3,c.bodyCount());
                assertExtents("UNION KEEP result",selectedCsg(c),20f,100f,150f);
                String rebuild=c.rebuildHistory();
                assertTrue("Rebuild failed: "+rebuild,!rebuild.contains("خطا"));
                assertEquals("Rebuild must preserve both originals",3,c.bodyCount());
                String undo=c.undoLastFeature();
                assertTrue("Undo did not remove Boolean feature: "+undo,undo.contains("UNION"));
                assertEquals("Undo must restore the two pre-Boolean bodies",2,c.bodyCount());
                String redo=c.redoLastFeature();
                assertTrue("Redo failed: "+redo,redo.contains("UNION"));
                assertEquals("Redo must restore output while keeping both originals",3,c.bodyCount());
                assertExtents("UNION KEEP redo result",selectedCsg(c),20f,100f,150f);
                Log.i(TAG,"BOOLEAN_KEEP_BOTH_RESULT after=3 rebuild=3 undo=2 redo=3 keepLeft=true keepRight=true");
            });
        }
    }

    @Test
    public void subtractKeepTargetPreservesOnlyTargetAcrossHistory(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
                String result=c.executeCommand("SUBTRACT 1 2 KEEP_TARGET");
                assertTrue("SUBTRACT KEEP_TARGET rejected: "+result,result.contains("Keep Target"));
                assertEquals("Keep Target must leave target + result",2,c.bodyCount());
                assertExtents("SUBTRACT KEEP_TARGET result",selectedCsg(c),20f,50f,100f);
                String rebuild=c.rebuildHistory();assertTrue("Rebuild failed: "+rebuild,!rebuild.contains("خطا"));
                assertEquals(2,c.bodyCount());
                String undo=c.undoLastFeature();assertTrue(undo.contains("SUBTRACT"));assertEquals(2,c.bodyCount());
                String redo=c.redoLastFeature();assertTrue(redo.contains("SUBTRACT"));assertEquals(2,c.bodyCount());
                assertExtents("SUBTRACT KEEP_TARGET redo result",selectedCsg(c),20f,50f,100f);
                Log.i(TAG,"BOOLEAN_KEEP_TARGET_RESULT after=2 rebuild=2 undo=2 redo=2 keepLeft=true keepRight=false");
            });
        }
    }

    @Test
    public void intersectKeepToolPreservesOnlyToolAcrossHistory(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
                String result=c.executeCommand("INTERSECT 1 2 KEEP_TOOL");
                assertTrue("INTERSECT KEEP_TOOL rejected: "+result,result.contains("Keep Tool"));
                assertEquals("Keep Tool must leave tool + result",2,c.bodyCount());
                assertExtents("INTERSECT KEEP_TOOL result",selectedCsg(c),20f,50f,100f);
                String rebuild=c.rebuildHistory();assertTrue("Rebuild failed: "+rebuild,!rebuild.contains("خطا"));
                assertEquals(2,c.bodyCount());
                String undo=c.undoLastFeature();assertTrue(undo.contains("INTERSECT"));assertEquals(2,c.bodyCount());
                String redo=c.redoLastFeature();assertTrue(redo.contains("INTERSECT"));assertEquals(2,c.bodyCount());
                assertExtents("INTERSECT KEEP_TOOL redo result",selectedCsg(c),20f,50f,100f);
                Log.i(TAG,"BOOLEAN_KEEP_TOOL_RESULT after=2 rebuild=2 undo=2 redo=2 keepLeft=false keepRight=true");
            });
        }
    }

    private static void makeOverlappingBodies(Shapr3DGuideCadCanvasView c){
        c.clearAll();
        String a=c.executeCommand("RECT 0 0 100 100");assertTrue("First RECT rejected: "+a,a.contains("مستطیل"));CadCanvasView.Entity first=c.selected;assertNotNull(first);
        String b=c.executeCommand("RECT 50 0 100 100");assertTrue("Second RECT rejected: "+b,b.contains("مستطیل"));CadCanvasView.Entity second=c.selected;assertNotNull(second);
        selectSketch(c,first);String e1=c.executeCommand("EXTRUDE 20");assertTrue("First EXTRUDE rejected: "+e1,e1.contains("ساخته شد"));
        selectSketch(c,second);String e2=c.executeCommand("EXTRUDE 20");assertTrue("Second EXTRUDE rejected: "+e2,e2.contains("ساخته شد"));
        assertEquals("Fixture must contain exactly two bodies",2,c.bodyCount());
    }

    private static void selectSketch(Shapr3DGuideCadCanvasView c,CadCanvasView.Entity e){
        c.selected=e;c.selectedObjects.clear();c.selectedObjects.add(e);
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){
        Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());assertNotNull("Production canvas not found",c);return c;
    }

    private static Shapr3DGuideCadCanvasView find(View v){
        if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}
        return null;
    }

    private static SolidCSG selectedCsg(Shapr3DGuideCadCanvasView c){
        try{Field selected=SolidCadCanvasView.class.getDeclaredField("selectedBody");selected.setAccessible(true);Object body=selected.get(c);assertNotNull("No selected Body",body);Field f=body.getClass().getDeclaredField("csg");f.setAccessible(true);return(SolidCSG)f.get(body);}catch(Exception e){throw new AssertionError("Cannot inspect selected Body",e);}
    }

    private static void assertExtents(String label,SolidCSG csg,float a,float b,float d){
        Bounds x=bounds(csg);float[] actual=sorted3(x.dx(),x.dy(),x.dz());float[] expected=sorted3(a,b,d);
        for(int i=0;i<3;i++)assertNear(label+" extent["+i+"]",expected[i],actual[i]);
    }

    private static Bounds bounds(SolidCSG csg){Bounds b=new Bounds();for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices)b.add(v.pos.x,v.pos.y,v.pos.z);assertTrue("Body bounds empty",b.seen);return b;}
    private static float[] sorted3(float a,float b,float c){float[] x={Math.abs(a),Math.abs(b),Math.abs(c)};java.util.Arrays.sort(x);return x;}
    private static void assertNear(String label,float expected,float actual){assertTrue(label+" expected="+expected+" actual="+actual,Math.abs(expected-actual)<=EPS);}

    private static final class Bounds{
        boolean seen;float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        void add(float x,float y,float z){seen=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}float dx(){return maxX-minX;}float dy(){return maxY-minY;}float dz(){return maxZ-minZ;}
    }
}
