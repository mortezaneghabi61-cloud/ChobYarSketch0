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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class Project3DExactInstrumentationTest {
    private static final String TAG="Manual26100Project3D";
    private static final int N=18;

    @Test public void exactLineCircleAndArcBecomeConstructionAndSurviveUndoRedo(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync(); scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();c.enterActiveSketchView(); String dxfBefore=c.buildDxf();
                double[] d=concat(line(0,0,20,100,0,20),circle(50,40,20,15),arc(0,0,20,10,0,Math.PI/2));
                String result=c.projectExactDescriptorsForTest(d);
                assertTrue(result,result.contains("Line 1")&&result.contains("Circle 1")&&result.contains("Arc 1"));
                List<Object> entities=entities(c);assertEquals(3,entities.size());int line=0,circle=0,arc=0;
                for(Object e:entities){assertTrue(isConstruction(e));String name=e.getClass().getSimpleName();
                    if("LineEntity".equals(name)){line++;near(100f,length(e),.01f);}
                    else if("CircleEntity".equals(name)){circle++;near(50f,f(e,"x"),.01f);near(40f,f(e,"y"),.01f);near(15f,f(e,"r"),.01f);}
                    else if("ArcEntity".equals(name)){arc++;near(10f,f(e,"r"),.01f);near(0f,f(e,"start"),.02f);near(90f,f(e,"sweep"),.02f);}}
                assertEquals(1,line);assertEquals(1,circle);assertEquals(1,arc);assertEquals(dxfBefore,c.buildDxf());
                c.undo();assertEquals(0,entities(c).size());assertTrue(c.redoSketch());assertEquals(3,entities(c).size());
                for(Object e:entities(c))assertTrue(isConstruction(e));assertEquals(dxfBefore,c.buildDxf());
                Log.i(TAG,"PROJECT3D_MAPPER_RESULT lines=1 circles=1 arcs=1 construction=true undo=true redo=true dxfStable=true");
            });
        }
    }

    @Test public void tiltedCircleIsRejectedInsteadOfFakedAsCircleOrPolyline(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync(); scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();c.enterActiveSketchView();double[] d=circle(0,0,20,25);d[11]=0;d[12]=1;d[13]=0;
                String result=c.projectExactDescriptorsForTest(d);assertTrue(result,result.contains("Unsupported 1"));assertEquals(0,entities(c).size());
                Log.i(TAG,"PROJECT3D_TILTED_CURVE_RESULT unsupported=1 entities=0 exactnessGuard=true");
            });
        }
    }

    @Test public void coincidentTopAndBottomEdgesAreDeduplicatedInSketchProjection(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            inst.waitForIdleSync(); scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView c=canvas(activity);c.clearAll();c.enterActiveSketchView();
                double[] d=concat(line(0,0,0,100,0,0),line(0,0,20,100,0,20),circle(50,40,0,15),circle(50,40,20,15));
                String result=c.projectExactDescriptorsForTest(d);assertTrue(result,result.contains("Line 1")&&result.contains("Circle 1")&&result.contains("Skipped 2"));assertEquals(2,entities(c).size());
                Log.i(TAG,"PROJECT3D_DEDUPE_RESULT lines=1 circles=1 skipped=2 entities=2");
            });
        }
    }

    private static double[] line(double x1,double y1,double z1,double x2,double y2,double z2){double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[2]=x1;r[3]=y1;r[4]=z1;r[5]=x2;r[6]=y2;r[7]=z2;r[11]=0;r[12]=0;r[13]=1;r[15]=0;r[16]=1;r[17]=1;return r;}
    private static double[] circle(double cx,double cy,double cz,double radius){double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_CIRCLE;r[2]=cx+radius;r[3]=cy;r[4]=cz;r[5]=cx+radius;r[6]=cy;r[7]=cz;r[8]=cx;r[9]=cy;r[10]=cz;r[11]=0;r[12]=0;r[13]=1;r[14]=radius;r[15]=0;r[16]=Math.PI*2;r[17]=1;return r;}
    private static double[] arc(double cx,double cy,double cz,double radius,double first,double last){double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_ARC;r[2]=cx+Math.cos(first)*radius;r[3]=cy+Math.sin(first)*radius;r[4]=cz;r[5]=cx+Math.cos(last)*radius;r[6]=cy+Math.sin(last)*radius;r[7]=cz;r[8]=cx;r[9]=cy;r[10]=cz;r[11]=0;r[12]=0;r[13]=1;r[14]=radius;r[15]=first;r[16]=last;r[17]=1;return r;}
    private static double[] concat(double[]... records){double[] out=new double[records.length*N];int p=0;for(double[] r:records){System.arraycopy(r,0,out,p,N);p+=N;}return out;}
    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity){Shapr3DGuideCadCanvasView c=find(activity.getWindow().getDecorView());assertNotNull(c);return c;}
    private static Shapr3DGuideCadCanvasView find(View v){if(v instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Shapr3DGuideCadCanvasView c=find(g.getChildAt(i));if(c!=null)return c;}}return null;}
    @SuppressWarnings("unchecked") private static List<Object> entities(Shapr3DGuideCadCanvasView c){try{Field f=CadCanvasView.class.getDeclaredField("entities");f.setAccessible(true);return new ArrayList<>((List<Object>)f.get(c));}catch(Exception e){throw new AssertionError(e);}}
    private static boolean isConstruction(Object e){for(Class<?> k=e.getClass();k!=null;k=k.getSuperclass()){try{Method m=k.getDeclaredMethod("isConstruction");m.setAccessible(true);return Boolean.TRUE.equals(m.invoke(e));}catch(NoSuchMethodException ignored){}catch(Exception x){throw new AssertionError(x);}}throw new AssertionError("isConstruction method not found");}
    private static float f(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.getFloat(o);}catch(Exception e){throw new AssertionError(e);}}
    private static float length(Object line){float x1=f(line,"x1"),y1=f(line,"y1"),x2=f(line,"x2"),y2=f(line,"y2");return(float)Math.hypot(x2-x1,y2-y1);}
    private static void near(float expected,float actual,float eps){assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);}
}
