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

/** Exact Boolean contracts against the production Shapr3DGuide canvas. */
@RunWith(AndroidJUnit4.class)
public final class BooleanCommandInstrumentationTest {
    private static final String TAG="BooleanCommandContract";
    private static final float EPS=.08f;

    @Test
    public void unionByBodyNumbersCreatesExactCombinedBodyAndHistoryUndoRedo(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
  inst.waitForIdleSync();
  scenario.onActivity(activity->{
      Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
      c.executeCommand("UNION 1 2");
      assertEquals("UNION must replace two bodies with one",1,c.bodyCount());
      Bounds b=bounds(selectedCsg(c));float[] e=sorted3(b.dx(),b.dy(),b.dz());
      assertNear("UNION thickness",20f,e[0]);assertNear("UNION width",100f,e[1]);assertNear("UNION length",150f,e[2]);
      c.rebuildHistory();
      assertEquals("UNION rebuild must preserve one body",1,c.bodyCount());
      Bounds rebuilt=bounds(selectedCsg(c));float[] re=sorted3(rebuilt.dx(),rebuilt.dy(),rebuilt.dz());
      assertNear("UNION rebuilt thickness",20f,re[0]);assertNear("UNION rebuilt width",100f,re[1]);assertNear("UNION rebuilt length",150f,re[2]);
      c.undoLastFeature();assertEquals("UNION undo must restore both source bodies",2,c.bodyCount());
      c.redoLastFeature();assertEquals("UNION redo must recreate one result body",1,c.bodyCount());
      Bounds redone=bounds(selectedCsg(c));float[] r=sorted3(redone.dx(),redone.dy(),redone.dz());
      assertNear("UNION redo thickness",20f,r[0]);assertNear("UNION redo width",100f,r[1]);assertNear("UNION redo length",150f,r[2]);
      Log.i(TAG,"BOOLEAN_UNION_RESULT bodyCount=1 extents=20x100x150 history=true undo=true redo=true faces="+selectedCsg(c).polygons().size());
  });
        }
    }

    @Test
    public void subtractByBodyNumbersLeavesExactHalfBlockAndPersists(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
  inst.waitForIdleSync();
  scenario.onActivity(activity->{
      Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
      c.executeCommand("SUBTRACT 1 2");
      assertEquals(1,c.bodyCount());Bounds b=bounds(selectedCsg(c));float[] e=sorted3(b.dx(),b.dy(),b.dz());
      assertNear("SUBTRACT thickness",20f,e[0]);assertNear("SUBTRACT remaining length",50f,e[1]);assertNear("SUBTRACT width",100f,e[2]);
      c.rebuildHistory();
      assertEquals("SUBTRACT rebuild must preserve one body",1,c.bodyCount());
      Bounds p=bounds(selectedCsg(c));float[] pe=sorted3(p.dx(),p.dy(),p.dz());
      assertNear("SUBTRACT rebuilt thickness",20f,pe[0]);assertNear("SUBTRACT rebuilt length",50f,pe[1]);assertNear("SUBTRACT rebuilt width",100f,pe[2]);
      Log.i(TAG,"BOOLEAN_SUBTRACT_RESULT bodyCount=1 extents=20x50x100 history=true faces="+selectedCsg(c).polygons().size());
  });
        }
    }

    @Test
    public void intersectByBodyNumbersCreatesExactOverlapAndPersists(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
  inst.waitForIdleSync();
  scenario.onActivity(activity->{
      Shapr3DGuideCadCanvasView c=canvas(activity);makeOverlappingBodies(c);
      c.executeCommand("INTERSECT 1 2");
      assertEquals(1,c.bodyCount());Bounds b=bounds(selectedCsg(c));float[] e=sorted3(b.dx(),b.dy(),b.dz());
      assertNear("INTERSECT thickness",20f,e[0]);assertNear("INTERSECT overlap length",50f,e[1]);assertNear("INTERSECT width",100f,e[2]);
      c.rebuildHistory();
      assertEquals("INTERSECT rebuild must preserve one body",1,c.bodyCount());
      Bounds p=bounds(selectedCsg(c));float[] pe=sorted3(p.dx(),p.dy(),p.dz());
      assertNear("INTERSECT rebuilt thickness",20f,pe[0]);assertNear("INTERSECT rebuilt length",50f,pe[1]);assertNear("INTERSECT rebuilt width",100f,pe[2]);
      Log.i(TAG,"BOOLEAN_INTERSECT_RESULT bodyCount=1 extents=20x50x100 history=true faces="+selectedCsg(c).polygons().size());
  });
        }
    }

    private static void makeOverlappingBodies(Shapr3DGuideCadCanvasView c){
        c.clearAll();
        c.executeCommand("RECT 0 0 100 100");CadCanvasView.Entity first=c.selected;assertNotNull("First rectangle must become selected",first);
        c.executeCommand("RECT 50 0 100 100");CadCanvasView.Entity second=c.selected;assertNotNull("Second rectangle must become selected",second);
        selectSketch(c,first);c.executeCommand("EXTRUDE 20");assertEquals("First extrusion must create one body",1,c.bodyCount());
        selectSketch(c,second);c.executeCommand("EXTRUDE 20");assertEquals("Second extrusion must create a second body",2,c.bodyCount());
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
    private static Bounds bounds(SolidCSG csg){Bounds b=new Bounds();for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices)b.add(v.pos.x,v.pos.y,v.pos.z);assertTrue("Body bounds empty",b.seen);return b;}
    private static float[] sorted3(float a,float b,float c){float[] x={Math.abs(a),Math.abs(b),Math.abs(c)};java.util.Arrays.sort(x);return x;}
    private static void assertNear(String label,float expected,float actual){assertTrue(label+" expected="+expected+" actual="+actual,Math.abs(expected-actual)<=EPS);}
    private static final class Bounds{
        boolean seen;float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        void add(float x,float y,float z){seen=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}float dx(){return maxX-minX;}float dy(){return maxY-minY;}float dz(){return maxZ-minZ;}
    }
}
