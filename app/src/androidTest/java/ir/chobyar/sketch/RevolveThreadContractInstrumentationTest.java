package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Durable contracts for the production Revolve / thread workflow.
 *
 * Geometry creation is separately native-gated. These tests protect the
 * select -> axis -> preview interaction state and the exact helical parameter
 * semantics shared by the UI and History: turns=abs(angle)/360 and
 * pitch=abs(height)/turns.
 */
@RunWith(AndroidJUnit4.class)
public final class RevolveThreadContractInstrumentationTest {

    @Test public void workspaceRevolveUsesProfileAxisPreviewSequence() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(false,"REGION");

        WorkspaceController.State profile=controller.begin(WorkspaceController.Tool.REVOLVE);
        assertEquals(WorkspaceController.Phase.SELECT_PRIMARY,profile.phase);
        assertFalse(profile.canCommit());
        assertEquals("Select a sketch profile or region to revolve.",profile.instruction());

        WorkspaceController.State axis=controller.primaryAccepted();
        assertEquals(WorkspaceController.Phase.SELECT_SECONDARY,axis.phase);
        assertFalse(axis.canCommit());
        assertEquals("Select a line to use as the revolve axis.",axis.instruction());

        WorkspaceController.State preview=controller.previewReady();
        assertEquals(WorkspaceController.Phase.PREVIEW,preview.phase);
        assertTrue(preview.canCommit());
        assertEquals("Set Angle and Height; for a threaded revolve use Angle = 360° × turns and Height = Pitch × turns.",preview.instruction());
        assertTrue(preview.instruction().contains("Height"));
        assertTrue(preview.instruction().contains("Pitch"));
    }

    @Test public void helicalPitchIsAxialHeightPerTurn() throws Exception {
        Object feature=newRevolveFeature(3600f,25.2f);
        float pitch=(Float)method(feature,"pitchMm").invoke(feature);
        assertEquals(2.52f,pitch,0.0001f);
    }

    @Test public void helicalDetailReportsHeightTurnsAndPitch() throws Exception {
        Object feature=newRevolveFeature(3600f,25.2f);
        String detail=(String)method(feature,"detail").invoke(feature);
        assertTrue(detail.contains("H 25.2 mm"));
        assertTrue(detail.contains("10 turns"));
        assertTrue(detail.contains("Pitch 2.52 mm"));
    }

    @Test public void zeroHeightRemainsOrdinaryRevolve() throws Exception {
        Object feature=newRevolveFeature(720f,0f);
        float pitch=(Float)method(feature,"pitchMm").invoke(feature);
        String detail=(String)method(feature,"detail").invoke(feature);
        assertEquals(0f,pitch,0.0001f);
        assertFalse(detail.contains("Pitch"));
        assertFalse(detail.contains(" • H "));
        assertTrue(detail.contains("720°"));
    }

    private static Object newRevolveFeature(float angleDeg,float heightMm) throws Exception {
        Class<?> type=Class.forName("ir.chobyar.sketch.AdvancedParametricSolidCadCanvasView$RevolveFeature");
        Constructor<?> ctor=type.getDeclaredConstructor(int.class,Object.class,Geometry3D.Plane3D.class,
                Object.class,boolean.class,float.class,float.class);
        ctor.setAccessible(true);
        return ctor.newInstance(1,new Object(),Geometry3D.xy(),null,false,angleDeg,heightMm);
    }

    private static Method method(Object target,String name) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }
}
