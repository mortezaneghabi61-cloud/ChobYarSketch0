package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public class K34CreateAuthorityInstrumentationTest {

    @Test public void exactLineCreateUsesDocumentHistoryAndStableIdAcrossUndoRedo() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            assertTrue(cad.executeCommand("LINE 10 20 50 20").contains("خط"));
            cad.requireSketchMirrorParity();
            String created=cad.exportSketchProjectState();
            assertEquals(1,entityCount(created));
            String id=onlyId(created);
            assertEquals(10.0,lineX1ById(created,id),1.0e-6);
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,entityCount(cad.exportSketchProjectState()));
            assertTrue(cad.sketchAuthorityCanRedo());

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            String redone=cad.exportSketchProjectState();
            assertEquals(1,entityCount(redone));
            assertEquals(id,onlyId(redone));
            assertEquals(10.0,lineX1ById(redone,id),1.0e-6);
            return true;
        });
    }

    @Test public void exactPrimitiveSequenceStaysOnOneTransactionalAuthorityChain() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.executeCommand("POINT 5 6");
            cad.requireSketchMirrorParity();
            cad.executeCommand("LINE 0 0 20 0");
            cad.requireSketchMirrorParity();
            cad.executeCommand("RECT 30 10 40 25");
            cad.requireSketchMirrorParity();
            cad.executeCommand("CIRCLE 100 50 12");
            cad.requireSketchMirrorParity();
            cad.executeCommand("ARC 140 60 20 180 90");
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            assertEquals(5,entityCount(out));
            assertEquals(5,ids(out).size());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            // One bridge hydration at the first model-authority boundary only.
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());
            assertTrue(cad.sketchAuthorityTransitionCount()>=5);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(4,entityCount(cad.exportSketchProjectState()));
            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(3,entityCount(cad.exportSketchProjectState()));
            return true;
        });
    }

    @Test public void exactCircleCreateSaveReopenPreservesAuthorityStableId() throws Exception {
        final String[] stableId=new String[1];
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("CIRCLE 25 35 9");
            cad.requireSketchMirrorParity();
            String out=cad.exportSketchProjectState();
            stableId[0]=onlyId(out);
            assertEquals(9.0,circleRadiusById(out,stableId[0]),1.0e-6);
            assertTrue(cad.sketchAuthorityHistoryActive());
            return out;
        });

        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            reopened.importSketchProjectState(saved);
            reopened.requireSketchMirrorParity();
            String out=reopened.exportSketchProjectState();
            assertTrue(ids(out).contains(stableId[0]));
            assertEquals(9.0,circleRadiusById(out,stableId[0]),1.0e-6);
            assertFalse(reopened.sketchAuthorityHistoryActive());
            return true;
        });
    }

    @Test public void stylusLineGestureKeepsAuthorityAcrossSelectionTapAndUndoRedo() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.setTool(CadCanvasView.TOOL_LINE);
            float[] a=screen(cad,10f,20f), b=screen(cad,50f,20f);
            stroke(cad,a,b,true);

            cad.requireSketchMirrorParity();
            String created=cad.exportSketchProjectState();
            assertEquals(1,entityCount(created));
            String id=onlyId(created);
            assertEquals("LINE",typeById(created,id));
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());

            // A pure selection tap is UI state only; it must not rehydrate and
            // destroy the truthful SketchDocument Undo chain.
            long syncAfterCreate=cad.sketchMirrorSyncCount();
            float[] mid=screen(cad,30f,20f);
            tap(cad,mid[0],mid[1],true);
            cad.requireSketchMirrorParity();
            assertEquals(syncAfterCreate,cad.sketchMirrorSyncCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,entityCount(cad.exportSketchProjectState()));
            assertTrue(cad.sketchAuthorityCanRedo());
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(id,onlyId(cad.exportSketchProjectState()));
            return true;
        });
    }

    @Test public void circleAndFreehandGesturesShareOneTransactionalAuthorityChain() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.setTool(CadCanvasView.TOOL_CIRCLE);
            stroke(cad,screen(cad,20f,30f),screen(cad,30f,30f),false);
            cad.requireSketchMirrorParity();

            cad.setTool(CadCanvasView.TOOL_FREE);
            freeStroke(cad,new float[][]{
                    screen(cad,60f,60f),
                    screen(cad,66f,63f),
                    screen(cad,72f,69f),
                    screen(cad,80f,72f)
            });
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            assertEquals(2,entityCount(out));
            assertEquals(1,typeCount(out,"CIRCLE"));
            assertEquals(1,typeCount(out,"POLYLINE"));
            assertEquals(2,ids(out).size());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            // Both gestures remain in one Document chain; only the first one
            // crosses the legacy -> transactional hydration boundary.
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1,entityCount(cad.exportSketchProjectState()));
            assertEquals(1,typeCount(cad.exportSketchProjectState(),"CIRCLE"));
            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,entityCount(cad.exportSketchProjectState()));
            return true;
        });
    }

    private static void stroke(K33MirroredCadCanvasView cad,float[] a,float[] b,boolean stylus) {
        long down=1_000L;
        send(cad,MotionEvent.ACTION_DOWN,a[0],a[1],down,down,stylus);
        send(cad,MotionEvent.ACTION_MOVE,b[0],b[1],down,down+16L,stylus);
        send(cad,MotionEvent.ACTION_UP,b[0],b[1],down,down+32L,stylus);
    }

    private static void freeStroke(K33MirroredCadCanvasView cad,float[][] points) {
        long down=2_000L;
        send(cad,MotionEvent.ACTION_DOWN,points[0][0],points[0][1],down,down,false);
        for(int i=1;i<points.length-1;i++)
            send(cad,MotionEvent.ACTION_MOVE,points[i][0],points[i][1],down,down+i*16L,false);
        float[] end=points[points.length-1];
        send(cad,MotionEvent.ACTION_UP,end[0],end[1],down,down+points.length*16L,false);
    }

    private static void tap(K33MirroredCadCanvasView cad,float x,float y,boolean stylus) {
        long down=3_000L;
        send(cad,MotionEvent.ACTION_DOWN,x,y,down,down,stylus);
        send(cad,MotionEvent.ACTION_UP,x,y,down,down+12L,stylus);
    }

    private static void send(K33MirroredCadCanvasView cad,int action,float x,float y,long down,long time,boolean stylus) {
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);
        if(stylus)e.setSource(InputDevice.SOURCE_STYLUS);
        cad.onTouchEvent(e);
        e.recycle();
    }

    private static float[] screen(K33MirroredCadCanvasView cad,float xMm,float yMm) throws Exception {
        JSONObject view=new JSONObject(cad.exportSketchProjectState()).getJSONObject("view");
        float scale=(float)view.getDouble("scale");
        float ox=(float)view.getDouble("offsetX"), oy=(float)view.getDouble("offsetY");
        return new float[]{ox+xMm*3f*scale,oy+yMm*3f*scale};
    }

    private static int entityCount(String raw) throws Exception {
        return new JSONObject(raw).getJSONArray("entities").length();
    }

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++){
            String id=rows.getJSONObject(i).getString("id").trim();
            assertFalse(id.isEmpty());
            assertTrue(out.add(id));
        }
        return out;
    }

    private static String onlyId(String raw) throws Exception {
        Set<String> values=ids(raw);
        assertEquals(1,values.size());
        return values.iterator().next();
    }

    private static String typeById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id")))return row.getString("type");
        }
        throw new AssertionError(id+" entity missing");
    }

    private static int typeCount(String raw,String type) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        int count=0;
        for(int i=0;i<rows.length();i++)if(type.equals(rows.getJSONObject(i).optString("type")))count++;
        return count;
    }

    private static double lineX1ById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id"))&&"LINE".equals(row.optString("type"))) return row.getDouble("x1");
        }
        throw new AssertionError(id+" line missing");
    }

    private static double circleRadiusById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id"))&&"CIRCLE".equals(row.optString("type"))) return row.getDouble("r");
        }
        throw new AssertionError(id+" circle missing");
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
