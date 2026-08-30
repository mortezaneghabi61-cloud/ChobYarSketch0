package ir.chobyar.sketch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;

import static org.junit.Assert.*;

/**
 * K3.6d installable-app fence: exercises the actual launcher activity and its
 * pause/relaunch recovery path rather than constructing only a canvas fixture.
 */
@RunWith(AndroidJUnit4.class)
public class K36dActivityLifecyclePersistenceInstrumentationTest {
    private WorkspaceRecoveryStore recovery;
    private ChobYarActivity activity;

    @Before public void before() {
        Context context=ApplicationProvider.getApplicationContext();
        recovery=new WorkspaceRecoveryStore(context);
        recovery.clear();
    }

    @After public void after() throws Exception {
        if(activity!=null)finish(activity);
        recovery.clear();
    }

    @Test public void launcherCreateAutoConstraintIsOneUndoAndSurvivesPauseRelaunchWithHistoryReset() throws Exception {
        activity=launch();
        K33MirroredCadCanvasView cad=canvas(activity);

        String hostId=onMain(() -> {
            cad.clearAll();
            cad.executeCommand("LINE 0 0 100 0");
            return cad.selected.stableId();
        });
        Set<String> before=ids(onMain(cad::exportSketchProjectState));

        onMain(() -> {
            cad.setTool(CadCanvasView.TOOL_LINE);
            float[] a=screen(cad,20f,25f);
            float[] b=screen(cad,100.4f,0.2f);
            stroke(cad,a,b);
            cad.requireSketchMirrorParity();
            return true;
        });

        String state=onMain(cad::exportSketchProjectState);
        String created=onlyNewId(before,state);
        SketchConstraint constraint=onMain(() -> cad.sketchConstraints().get(0));
        assertEquals(SketchConstraint.Kind.COINCIDENT,constraint.kind);
        assertEquals(created,constraint.primaryEntityId);
        assertEquals(1,constraint.primaryPointIndex);
        assertEquals(hostId,constraint.secondaryEntityId);
        assertEquals(1,constraint.secondaryPointIndex);

        // Real interaction contract: Create + generated Coincident is one Undo.
        onMain(() -> { cad.undo(); cad.requireSketchMirrorParity(); return true; });
        assertFalse(ids(onMain(cad::exportSketchProjectState)).contains(created));
        assertEquals(0,(int)onMain(cad::sketchConstraintCount));
        assertTrue(onMain(cad::redoSketch));
        assertTrue(ids(onMain(cad::exportSketchProjectState)).contains(created));
        assertEquals(1,(int)onMain(cad::sketchConstraintCount));

        // ChobYarActivity.onPause synchronously persists the recovery payload.
        finish(activity);
        activity=null;
        assertTrue(recovery.hasSnapshot());

        activity=launch();
        K33MirroredCadCanvasView reopened=canvas(activity);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String reopenedState=onMain(reopened::exportSketchProjectState);
        assertTrue(ids(reopenedState).contains(hostId));
        assertTrue(ids(reopenedState).contains(created));
        assertEquals(1,(int)onMain(reopened::sketchConstraintCount));
        SketchConstraint reopenedConstraint=onMain(() -> reopened.sketchConstraints().get(0));
        assertEquals(constraint.id,reopenedConstraint.id);
        assertEquals(constraint.primaryEntityId,reopenedConstraint.primaryEntityId);
        assertEquals(constraint.primaryPointIndex,reopenedConstraint.primaryPointIndex);
        assertEquals(constraint.secondaryEntityId,reopenedConstraint.secondaryEntityId);
        assertEquals(constraint.secondaryPointIndex,reopenedConstraint.secondaryPointIndex);
        onMain(() -> { reopened.requireSketchMirrorParity(); return true; });

        // File/recovery Open restores relationships, not a previous session's Undo stack.
        assertFalse(onMain(reopened::sketchAuthorityCanUndo));
        assertFalse(onMain(reopened::sketchAuthorityHistoryActive));
        assertEquals(0,(int)onMain(reopened::legacyMigratedConstraintTruthCount));
    }

    @Test public void launcherPointOnEntityCreateIsOneUndoAndSurvivesPauseRelaunchWithHistoryReset() throws Exception {
        activity=launch();
        K33MirroredCadCanvasView cad=canvas(activity);

        String hostId=onMain(() -> {
            cad.clearAll();
            cad.executeCommand("LINE 0 0 100 0");
            return cad.selected.stableId();
        });
        Set<String> before=ids(onMain(cad::exportSketchProjectState));

        onMain(() -> {
            cad.setTool(CadCanvasView.TOOL_LINE);
            float[] a=screen(cad,20f,25f);
            float[] b=screen(cad,30f,0.2f);
            stroke(cad,a,b);
            cad.requireSketchMirrorParity();
            return true;
        });

        String state=onMain(cad::exportSketchProjectState);
        String created=onlyNewId(before,state);
        assertEquals("ON_EDGE",onMain(cad::sketchLastModelSnapKind));
        SketchConstraint constraint=onMain(() -> cad.sketchConstraints().get(0));
        assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY,constraint.kind);
        assertEquals(created,constraint.primaryEntityId);
        assertEquals(1,constraint.primaryPointIndex);
        assertEquals(hostId,constraint.secondaryEntityId);
        assertEquals(-1,constraint.secondaryPointIndex);

        // Real interaction contract: Create + generated Point-on-Entity is one Undo.
        onMain(() -> { cad.undo(); cad.requireSketchMirrorParity(); return true; });
        assertFalse(ids(onMain(cad::exportSketchProjectState)).contains(created));
        assertEquals(0,(int)onMain(cad::sketchConstraintCount));
        assertTrue(onMain(cad::redoSketch));
        assertTrue(ids(onMain(cad::exportSketchProjectState)).contains(created));
        assertEquals(1,(int)onMain(cad::sketchConstraintCount));

        finish(activity);
        activity=null;
        assertTrue(recovery.hasSnapshot());

        activity=launch();
        K33MirroredCadCanvasView reopened=canvas(activity);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String reopenedState=onMain(reopened::exportSketchProjectState);
        assertTrue(ids(reopenedState).contains(hostId));
        assertTrue(ids(reopenedState).contains(created));
        assertEquals(1,(int)onMain(reopened::sketchConstraintCount));
        SketchConstraint reopenedConstraint=onMain(() -> reopened.sketchConstraints().get(0));
        assertEquals(constraint.id,reopenedConstraint.id);
        assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY,reopenedConstraint.kind);
        assertEquals(constraint.primaryEntityId,reopenedConstraint.primaryEntityId);
        assertEquals(constraint.primaryPointIndex,reopenedConstraint.primaryPointIndex);
        assertEquals(constraint.secondaryEntityId,reopenedConstraint.secondaryEntityId);
        assertEquals(constraint.secondaryPointIndex,reopenedConstraint.secondaryPointIndex);
        onMain(() -> { reopened.requireSketchMirrorParity(); return true; });

        assertFalse(onMain(reopened::sketchAuthorityCanUndo));
        assertFalse(onMain(reopened::sketchAuthorityHistoryActive));
        assertEquals(0,(int)onMain(reopened::legacyMigratedConstraintTruthCount));
    }

    private static ChobYarActivity launch() {
        Context context=ApplicationProvider.getApplicationContext();
        Intent intent=new Intent(context,ChobYarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity started=InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(started instanceof ChobYarActivity);
        return (ChobYarActivity)started;
    }

    private static void finish(ChobYarActivity value) throws Exception {
        onMain(() -> { value.finish(); return true; });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static K33MirroredCadCanvasView canvas(ChobYarActivity value) throws Exception {
        Field f=ChobYarActivity.class.getDeclaredField("cad");
        f.setAccessible(true);
        Object cad=f.get(value);
        assertTrue(cad instanceof K33MirroredCadCanvasView);
        return (K33MirroredCadCanvasView)cad;
    }

    private static void stroke(K33MirroredCadCanvasView cad,float[] a,float[] b) {
        long down=9_000L;
        send(cad,MotionEvent.ACTION_DOWN,a[0],a[1],down,down);
        send(cad,MotionEvent.ACTION_MOVE,b[0],b[1],down,down+16L);
        send(cad,MotionEvent.ACTION_UP,b[0],b[1],down,down+32L);
    }

    private static void send(K33MirroredCadCanvasView cad,int action,float x,float y,long down,long time) {
        MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);
        event.setSource(InputDevice.SOURCE_STYLUS);
        cad.onTouchEvent(event);
        event.recycle();
    }

    private static float[] screen(K33MirroredCadCanvasView cad,float xMm,float yMm) throws Exception {
        JSONObject view=new JSONObject(cad.exportSketchProjectState()).getJSONObject("view");
        float scale=(float)view.getDouble("scale");
        float ox=(float)view.getDouble("offsetX"),oy=(float)view.getDouble("offsetY");
        return new float[]{ox+xMm*3f*scale,oy+yMm*3f*scale};
    }

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static String onlyNewId(Set<String> before,String raw) throws Exception {
        Set<String> after=ids(raw);after.removeAll(before);
        assertEquals(1,after.size());return after.iterator().next();
    }

    private static <T>T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
