package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.PointF;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Associative Project contracts: source rebuild + stable reference metadata through Sketch snapshots. */
@RunWith(AndroidJUnit4.class)
public final class AssociativeProjectInstrumentationTest {
    private static final String TAG="Manual26100AssocProject";
    private static final int N=18;

    @Test
    public void sourceSketchEditAutomaticallyRefreshesProjectReference(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();c.enterActiveSketchView();
                assertTrue(c.executeCommand("RECT 0 0 100 60").contains("مستطیل"));
                Object rect=selectedSketch(c);assertNotNull(rect);
                assertTrue(c.executeCommand("EXTRUDE 20").contains("ساخته شد"));
                Object sourceBody=selectedBody(c);assertNotNull(sourceBody);
                c.enterActiveSketchView();

                Shapr3DGuideCadCanvasView.ProjectDescriptorProvider provider=body->line(0,0,20,rectWidth(rect),0,20);
                String project=c.projectAssociativeForTest(sourceBody,provider);
                assertTrue("Associative Project was not created: "+project,project.contains("Associative #"));
                assertEquals(1,c.associativeProjectEntityCountForTest());
                near(100f,projectedLineLength(c),.02f);

                setSelectedSketch(c,rect);
                String dim=c.applySelectedDimension("140 60");
                assertTrue("Source dimension edit failed: "+dim,dim.contains("140"));
                assertEquals("Project rebuild must replace, not duplicate, its reference",1,c.associativeProjectEntityCountForTest());
                near(140f,projectedLineLength(c),.02f);
                assertTrue("Projected reference must stay Construction",projectedConstruction(c));
                Log.i(TAG,"ASSOC_PROJECT_REBUILD_RESULT before=100 after=140 references=1 construction=true automatic=true");
            });
        }
    }

    @Test
    public void projectCreationUndoRedoUsesStableReferenceTagWithoutResurrection(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();c.enterActiveSketchView();
                c.executeCommand("RECT 0 0 80 50");Object rect=selectedSketch(c);
                c.executeCommand("EXTRUDE 18");Object sourceBody=selectedBody(c);assertNotNull(sourceBody);c.enterActiveSketchView();
                Shapr3DGuideCadCanvasView.ProjectDescriptorProvider provider=body->line(0,0,18,rectWidth(rect),0,18);
                String project=c.projectAssociativeForTest(sourceBody,provider);assertTrue(project,project.contains("Associative #"));
                assertEquals(1,c.associativeProjectEntityCountForTest());

                c.undo();
                assertEquals("Undo of Project creation must remove the whole reference",0,c.associativeProjectEntityCountForTest());
                c.rebuildHistory();
                assertEquals("Inactive Project must not resurrect during History rebuild",0,c.associativeProjectEntityCountForTest());

                assertTrue("Project redo snapshot must exist",c.redoSketch());
                assertEquals("Redo must restore exactly one tagged Project reference",1,c.associativeProjectEntityCountForTest());
                near(80f,projectedLineLength(c),.02f);
                Log.i(TAG,"ASSOC_PROJECT_UNDO_REDO_RESULT create=1 undo=0 rebuild=0 redo=1 resurrect=false tagStable=true");
            });
        }
    }

    private static double[] line(double x1,double y1,double z1,double x2,double y2,double z2){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[2]=x1;r[3]=y1;r[4]=z1;r[5]=x2;r[6]=y2;r[7]=z2;r[11]=0;r[12]=0;r[13]=1;r[15]=0;r[16]=1;r[17]=1;return r;
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());assertNotNull(c);return c;}
    private static Shapr3DGuideCadCanvasView find(View v){if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}return null;}

    private static Object selectedSketch(Shapr3DGuideCadCanvasView c){try{Field f=CadCanvasView.class.getDeclaredField("selected");f.setAccessible(true);return f.get(c);}catch(Exception e){throw new AssertionError(e);}}
    private static void setSelectedSketch(Shapr3DGuideCadCanvasView c,Object value){try{Field f=CadCanvasView.class.getDeclaredField("selected");f.setAccessible(true);f.set(c,value);}catch(Exception e){throw new AssertionError(e);}}
    private static Object selectedBody(Shapr3DGuideCadCanvasView c){try{Field f=SolidCadCanvasView.class.getDeclaredField("selectedBody");f.setAccessible(true);return f.get(c);}catch(Exception e){throw new AssertionError(e);}}

    @SuppressWarnings("unchecked")
    private static List<Object> entities(Shapr3DGuideCadCanvasView c){try{Field f=CadCanvasView.class.getDeclaredField("entities");f.setAccessible(true);return new ArrayList<>((List<Object>)f.get(c));}catch(Exception e){throw new AssertionError(e);}}

    private static float rectWidth(Object rect){
        try{Field f=rect.getClass().getDeclaredField("p");f.setAccessible(true);PointF[] p=(PointF[])f.get(rect);return(float)Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y);}catch(Exception e){throw new AssertionError(e);}
    }

    private static Object projectedLine(Shapr3DGuideCadCanvasView c){
        for(Object e:entities(c)){
            if(!"LineEntity".equals(e.getClass().getSimpleName()))continue;
            String tag=String.valueOf(call(e,"getReferenceTag"));
            if(tag.startsWith("PROJECTREF:"))return e;
        }
        throw new AssertionError("Tagged Project line not found");
    }
    private static float projectedLineLength(Shapr3DGuideCadCanvasView c){Object e=projectedLine(c);return(float)Math.hypot(f(e,"x2")-f(e,"x1"),f(e,"y2")-f(e,"y1"));}
    private static boolean projectedConstruction(Shapr3DGuideCadCanvasView c){return Boolean.TRUE.equals(call(projectedLine(c),"isConstruction"));}
    private static Object call(Object o,String name){for(Class<?> k=o.getClass();k!=null;k=k.getSuperclass())try{Method m=k.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}catch(NoSuchMethodException ignored){}catch(Exception e){throw new AssertionError(e);}throw new AssertionError(name+" not found");}
    private static float f(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.getFloat(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void near(float expected,float actual,float eps){assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);}
}
