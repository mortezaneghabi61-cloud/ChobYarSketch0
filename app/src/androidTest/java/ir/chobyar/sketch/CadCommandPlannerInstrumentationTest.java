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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Natural-language planner must terminate at the existing production command authority. */
@RunWith(AndroidJUnit4.class)
public final class CadCommandPlannerInstrumentationTest {
    private static final String TAG="CadCommandPlanner";

    @Test public void englishPlanCreatesRealProductionBody(){
        runPlan("create a rectangle 120 by 80 then extrude it 30 mm",30f);
    }

    @Test public void persianPlanCreatesRealProductionBody(){
        runPlan("مستطیل ۱۲۰ در ۸۰ بساز و ۲۰ میلی متر اکسترود کن",20f);
    }

    private static void runPlan(String instruction,float expectedHeight){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();
                CadCommandPlanner.Plan plan=CadCommandPlanner.plan(instruction);
                assertTrue(plan.error(),plan.ok());
                assertEquals(2,plan.commands().size());
                String first=c.executeCommand(plan.commands().get(0));
                assertTrue("RECT rejected: "+first,first.contains("Rectangle"));
                String second=c.executeCommand(plan.commands().get(1));
                assertTrue("EXTRUDE rejected: "+second,second.contains("created"));
                assertEquals(1,c.bodyCount());assertTrue(c.is3DOverview());

                float[] extents=extents(selectedCsg(c));
                near(expectedHeight,extents[0]);near(80f,extents[1]);near(120f,extents[2]);
                String rebuilt=c.rebuildHistory();
                assertTrue("History rebuild failed: "+rebuilt,!rebuilt.contains("Error"));
                Log.i(TAG,"NATURAL_COMMAND_RESULT language="+(instruction.startsWith("create")?"en":"fa")
                        +" commands="+plan.commands()+" body=1 extents="+extents[0]+"x"+extents[1]+"x"+extents[2]+" history=true");
            });
        }
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){
        Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());
        assertNotNull("Production canvas not found",c);return c;
    }

    private static Shapr3DGuideCadCanvasView find(View v){
        if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}
        return null;
    }

    private static SolidCSG selectedCsg(Shapr3DGuideCadCanvasView c){
        try{
            Field sf=SolidCadCanvasView.class.getDeclaredField("selectedBody");sf.setAccessible(true);
            Object body=sf.get(c);assertNotNull("No selected body",body);
            Field cf=body.getClass().getDeclaredField("csg");cf.setAccessible(true);
            SolidCSG csg=(SolidCSG)cf.get(body);assertNotNull(csg);return csg;
        }catch(Exception e){throw new AssertionError("Cannot inspect selected body",e);}
    }

    private static float[] extents(SolidCSG csg){
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices){
            minX=Math.min(minX,v.pos.x);minY=Math.min(minY,v.pos.y);minZ=Math.min(minZ,v.pos.z);
            maxX=Math.max(maxX,v.pos.x);maxY=Math.max(maxY,v.pos.y);maxZ=Math.max(maxZ,v.pos.z);
        }
        float[] x={Math.abs(maxX-minX),Math.abs(maxY-minY),Math.abs(maxZ-minZ)};java.util.Arrays.sort(x);return x;
    }

    private static void near(float expected,float actual){
        assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=.08f);
    }
}
