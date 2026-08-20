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

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Manual 26.100 contracts for Construction geometry and Sketch Project. */
@RunWith(AndroidJUnit4.class)
public final class ConstructionProjectInstrumentationTest {
    private static final String TAG="Manual26100Sketch";

    @Test
    public void constructionIsRealPersistentSketchStateAndStaysOutOfDxf(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();
                String made=c.executeCommand("LINE 10 20 110 20");
                assertTrue(made.contains("خط"));assertNotNull(c.selected);
                assertFalse(c.selected.isConstruction());

                String state=c.executeCommand("CONSTRUCTION");
                assertTrue("CONSTRUCTION command rejected: "+state,state.contains("Construction"));
                assertTrue("Selected entity did not become Construction",c.selected.isConstruction());
                assertFalse("Construction geometry leaked into DXF",c.buildDxf().contains("\nLINE\n"));

                CadCanvasView.Entity hit=c.coreFindHit(60f,20f);
                assertNotNull("Construction geometry must remain selectable",hit);
                assertTrue(hit.isConstruction());

                c.undo();
                assertEquals(1,c.entities.size());
                assertFalse("Undo did not restore Normal geometry",c.entities.get(0).isConstruction());
                assertTrue("Redo unavailable after Construction undo",c.redoSketch());
                assertEquals(1,c.entities.size());
                assertTrue("Redo did not restore Construction state",c.entities.get(0).isConstruction());
                Log.i(TAG,"CONSTRUCTION_RESULT persistent=true selectable=true dxfExcluded=true undo=true redo=true");
            });
        }
    }

    @Test
    public void sketchProjectCreatesIndependentConstructionReference(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();
                c.executeCommand("LINE 0 0 80 0");
                assertEquals(1,c.entities.size());
                String result=projectSketch(c);
                assertTrue("Project Sketch failed: "+result,result.contains("Projection"));
                assertEquals("Project must create an independent reference",2,c.entities.size());
                assertTrue("Projected geometry must be Construction",c.selected.isConstruction());
                int normal=0,construction=0;
                for(CadCanvasView.Entity e:c.entities){if(e.isConstruction())construction++;else normal++;}
                assertEquals(1,normal);assertEquals(1,construction);
                String dxf=c.buildDxf();
                assertEquals("Only source geometry should be exported",1,count(dxf,"\nLINE\n"));
                Log.i(TAG,"PROJECT_SKETCH_RESULT source=1 projected=1 construction=true dxfNormalLines=1");
            });
        }
    }

    private static String projectSketch(Shapr3DGuideCadCanvasView c){
        try{
            Method m=OcctShaprCadCanvasView.class.getDeclaredMethod("projectReference");m.setAccessible(true);
            return String.valueOf(m.invoke(c));
        }catch(Exception e){throw new AssertionError("Cannot invoke production Project Sketch",e);}
    }
    private static int count(String s,String needle){int n=0,p=0;while((p=s.indexOf(needle,p))>=0){n++;p+=needle.length();}return n;}
    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());assertNotNull(c);return c;}
    private static Shapr3DGuideCadCanvasView find(View v){if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}return null;}
}
