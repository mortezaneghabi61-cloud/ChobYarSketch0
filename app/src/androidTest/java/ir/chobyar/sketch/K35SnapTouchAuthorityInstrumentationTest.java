package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

@RunWith(AndroidJUnit4.class)
public class K35SnapTouchAuthorityInstrumentationTest {
    private static final double EPS=1.0e-4;

    @Test public void stylusCreateUsesModelEndpointSnapAndKeepsTransactionalHistory() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("LINE 0 0 100 0");
            cad.requireSketchMirrorParity();
            Set<String> before=ids(cad.exportSketchProjectState());
            long snapBefore=cad.sketchModelSnapCount();

            cad.setTool(CadCanvasView.TOOL_LINE);
            // Beyond the segment endpoint, nearest-edge projection clamps to the
            // endpoint as well. The model service's deterministic rank therefore
            // selects ENDPOINT rather than ON_EDGE for this true endpoint case.
            stroke(cad,screen(cad,20f,25f),screen(cad,100.4f,0.2f),true);
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            Set<String> after=ids(out);
            assertEquals(2,after.size());
            after.removeAll(before);
            assertEquals(1,after.size());
            String created=after.iterator().next();
            assertEquals(100.0,lineX2(out,created),EPS);
            assertEquals(0.0,lineY2(out,created),EPS);
            assertTrue(cad.sketchModelSnapCount()>snapBefore);
            assertEquals("ENDPOINT",cad.sketchLastModelSnapKind());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1,entityCount(cad.exportSketchProjectState()));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertTrue(ids(cad.exportSketchProjectState()).contains(created));
            return true;
        });
    }

    @Test public void arcOutsideSweepDoesNotPhantomSnapToSupportingCircle() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            // Radius sqrt(15^2 + 15^2): (-15,-15) lies on the supporting circle
            // around 225 degrees, while the real arc exists only from 0..90.
            // The radius is > the 10 mm model snap radius, so CENTER cannot mask
            // the test. (-15,-15) is also >5.8 mm from every 10 mm grid point.
            cad.executeCommand("ARC 0 0 21.213203 0 90");
            cad.requireSketchMirrorParity();
            Set<String> before=ids(cad.exportSketchProjectState());

            cad.setTool(CadCanvasView.TOOL_POINT);
            tap(cad,screen(cad,-15f,-15f),true);
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            String point=onlyNewIdOfType(before,out,"POINT");
            assertEquals("POINT",entity(out,point).getString("type"));
            assertEquals(-15.0,pointX(out,point),EPS);
            assertEquals(-15.0,pointY(out,point),EPS);
            return true;
        });
    }

    @Test public void gridFallbackStillWorksWhenLegacyGeometrySnapIsSuppressed() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.setTool(CadCanvasView.TOOL_POINT);
            tap(cad,screen(cad,12f,12f),false);
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            String id=onlyId(out);
            assertEquals(10.0,pointX(out,id),EPS);
            assertEquals(10.0,pointY(out,id),EPS);
            assertTrue(cad.sketchAuthorityHistoryActive());
            return true;
        });
    }

    private static void stroke(K33MirroredCadCanvasView cad,float[] a,float[] b,boolean stylus){
        long down=4_000L;
        send(cad,MotionEvent.ACTION_DOWN,a[0],a[1],down,down,stylus);
        send(cad,MotionEvent.ACTION_MOVE,b[0],b[1],down,down+16L,stylus);
        send(cad,MotionEvent.ACTION_UP,b[0],b[1],down,down+32L,stylus);
    }

    private static void tap(K33MirroredCadCanvasView cad,float[] p,boolean stylus){
        long down=5_000L;
        send(cad,MotionEvent.ACTION_DOWN,p[0],p[1],down,down,stylus);
        send(cad,MotionEvent.ACTION_UP,p[0],p[1],down,down+12L,stylus);
    }

    private static void send(K33MirroredCadCanvasView cad,int action,float x,float y,long down,long time,boolean stylus){
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);
        if(stylus)e.setSource(InputDevice.SOURCE_STYLUS);
        cad.onTouchEvent(e);
        e.recycle();
    }

    private static float[] screen(K33MirroredCadCanvasView cad,float xMm,float yMm)throws Exception{
        JSONObject view=new JSONObject(cad.exportSketchProjectState()).getJSONObject("view");
        float scale=(float)view.getDouble("scale");
        float ox=(float)view.getDouble("offsetX"),oy=(float)view.getDouble("offsetY");
        return new float[]{ox+xMm*3f*scale,oy+yMm*3f*scale};
    }

    private static int entityCount(String raw)throws Exception{
        return new JSONObject(raw).getJSONArray("entities").length();
    }

    private static Set<String> ids(String raw)throws Exception{
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static String onlyId(String raw)throws Exception{
        Set<String> values=ids(raw);assertEquals(1,values.size());return values.iterator().next();
    }

    private static String onlyNewIdOfType(Set<String> before,String raw,String type)throws Exception{
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        String found=null;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            String id=row.getString("id");
            if(before.contains(id)||!type.equals(row.optString("type")))continue;
            if(found!=null)throw new AssertionError("multiple new "+type+" entities");
            found=id;
        }
        if(found==null)throw new AssertionError("new "+type+" entity missing; state="+raw);
        return found;
    }

    private static double lineX2(String raw,String id)throws Exception{
        JSONObject row=entity(raw,id);return row.getDouble("x2");
    }
    private static double lineY2(String raw,String id)throws Exception{
        JSONObject row=entity(raw,id);return row.getDouble("y2");
    }
    private static double pointX(String raw,String id)throws Exception{
        JSONObject row=entity(raw,id);return row.getDouble("x");
    }
    private static double pointY(String raw,String id)throws Exception{
        JSONObject row=entity(raw,id);return row.getDouble("y");
    }
    private static JSONObject entity(String raw,String id)throws Exception{
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id")))return row;
        }
        throw new AssertionError(id+" missing");
    }

    private static <T>T onMain(Callable<T> callable)throws Exception{
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
