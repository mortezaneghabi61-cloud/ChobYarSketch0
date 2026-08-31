package ir.chobyar.sketch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** K3.10 launcher/lifecycle fence for model-owned driving dimensions. */
@RunWith(AndroidJUnit4.class)
public final class K310DrivingDimensionActivityLifecycleInstrumentationTest {
    private WorkspaceRecoveryStore recovery;
    private ChobYarActivity activity;

    @Before public void before() {
        Context context=ApplicationProvider.getApplicationContext();
        recovery=new WorkspaceRecoveryStore(context);
        recovery.clear();
    }

    @After public void after() throws Exception {
        if(activity!=null) finish(activity);
        recovery.clear();
    }

    @Test public void launcherDrivingDimensionSurvivesPauseRelaunchAsModelTruthWithHistoryReset() throws Exception {
        activity=launch();
        K33MirroredCadCanvasView cad=canvas(activity);

        String id=onMain(() -> {
            cad.clearAll();
            cad.executeCommand("LINE 5 5 35 5");
            String stableId=cad.selected.stableId();
            String result=cad.applySelectedDimension("75");
            assertTrue(result,result.contains("75"));
            cad.requireSketchMirrorParity();
            assertEquals(75.0,((SketchGeometry.Line)entity(cad,stableId)).lengthMm(),1.0e-5);
            SketchConstraint driver=singleDistance(cad,stableId);
            assertEquals(75.0,driver.value,1.0e-9);
            return stableId;
        });
        String constraintId=onMain(() -> singleDistance(cad,id).id);

        // Real ChobYarActivity.onPause persistence path.
        finish(activity);
        activity=null;
        assertTrue(recovery.hasSnapshot());

        activity=launch();
        K33MirroredCadCanvasView reopened=canvas(activity);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        onMain(() -> {
            reopened.requireSketchMirrorParity();
            assertEquals(75.0,((SketchGeometry.Line)entity(reopened,id)).lengthMm(),1.0e-5);
            SketchConstraint reopenedDriver=singleDistance(reopened,id);
            assertEquals(constraintId,reopenedDriver.id);
            assertEquals(75.0,reopenedDriver.value,1.0e-9);
            // Reopen restores persistent model truth, never the prior session's Undo stack.
            assertFalse(reopened.sketchAuthorityCanUndo());
            assertFalse(reopened.sketchAuthorityHistoryActive());
            assertEquals(0,reopened.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    private static SketchEntity entity(K33MirroredCadCanvasView cad,String id) {
        for(SketchEntity entity:cad.sketchMirrorEntities()) if(id.equals(entity.id())) return entity;
        throw new AssertionError("Model entity not found: "+id+"; mirrorError="+cad.sketchMirrorError());
    }

    private static SketchConstraint singleDistance(K33MirroredCadCanvasView cad,String id) {
        SketchConstraint found=null;
        for(SketchConstraint c:cad.sketchConstraints()) {
            if(c.kind==SketchConstraint.Kind.DISTANCE&&id.equals(c.primaryEntityId)) {
                if(found!=null) throw new AssertionError("Duplicate model-owned DISTANCE for "+id);
                found=c;
            }
        }
        if(found==null) throw new AssertionError("Missing model-owned DISTANCE for "+id);
        return found;
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
        long deadline=System.currentTimeMillis()+5000L;
        while(!value.isDestroyed()&&System.currentTimeMillis()<deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(25L);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("Activity did not reach destroyed state",value.isDestroyed());
    }

    private static K33MirroredCadCanvasView canvas(ChobYarActivity value) throws Exception {
        Field f=ChobYarActivity.class.getDeclaredField("cad");
        f.setAccessible(true);
        Object cad=f.get(value);
        assertTrue(cad instanceof K33MirroredCadCanvasView);
        return (K33MirroredCadCanvasView)cad;
    }

    private static <T>T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
