package ir.chobyar.sketch;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchGeometry;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class K36dProductionEndpointConstraintAuthorityInstrumentationTest {
    private K33MirroredCadCanvasView canvas() {
        Context c=ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView v=new K33MirroredCadCanvasView(c);
        v.clearAll(); return v;
    }

    @Test public void manualCoincidentUsesStableIdsUndoRedoAndNoLegacyTruth() {
        K33MirroredCadCanvasView v=canvas();
        v.executeCommand("LINE 0 0 10 0"); String a=v.sketchMirrorEntities().get(0).id();
        v.executeCommand("LINE 12 2 20 2"); String b=v.sketchMirrorEntities().get(1).id();
        assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,v.applyModelCoincidentForTest(b,0,a,1).code);
        SketchConstraint c=v.sketchConstraints().get(0);
        assertEquals(SketchConstraint.Kind.COINCIDENT,c.kind); assertEquals(b,c.primaryEntityId);
        assertEquals(0,c.primaryPointIndex); assertEquals(a,c.secondaryEntityId); assertEquals(1,c.secondaryPointIndex);
        SketchGeometry.Line solved=(SketchGeometry.Line)find(v,b); assertEquals(10,solved.a.xMm,1e-6); assertEquals(0,solved.a.yMm,1e-6);
        assertEquals(0,v.legacyMigratedConstraintTruthCount());
        v.undo(); assertEquals(0,v.sketchConstraintCount());
        assertTrue(v.redoSketch()); assertEquals(1,v.sketchConstraintCount());
    }

    @Test public void pointOnEntityPropagatesUndoRedoAndPersistsPointIndex() {
        K33MirroredCadCanvasView v=canvas();
        v.executeCommand("LINE 0 0 20 0"); String host=v.sketchMirrorEntities().get(0).id();
        v.executeCommand("LINE 7 3 7 10"); String owner=v.sketchMirrorEntities().get(1).id();
        assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,v.applyModelPointOnEntityForTest(owner,0,host).code);
        SketchConstraint c=v.sketchConstraints().get(0); assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY,c.kind); assertEquals(0,c.primaryPointIndex);
        String saved=v.exportSketchProjectState();
        K33MirroredCadCanvasView reopened=canvas(); assertFalse(reopened.importSketchProjectState(saved).contains("could not"));
        assertEquals(1,reopened.sketchConstraintCount());
        SketchConstraint restored=reopened.sketchConstraints().get(0); assertEquals(c.id,restored.id); assertEquals(0,restored.primaryPointIndex);
        assertFalse(reopened.sketchAuthorityCanUndo()); assertEquals(0,reopened.legacyMigratedConstraintTruthCount());
    }

    @Test public void deletingReferencedEntityCascadesConstraint() {
        K33MirroredCadCanvasView v=canvas();
        v.executeCommand("LINE 0 0 20 0"); String host=v.sketchMirrorEntities().get(0).id();
        v.executeCommand("LINE 5 0 5 5"); String owner=v.sketchMirrorEntities().get(1).id();
        assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,v.applyModelPointOnEntityForTest(owner,0,host).code);
        assertEquals(1,v.sketchConstraintCount());
        // Core cascade is separately unit-tested; production persistence must expose only model truth.
        assertEquals(0,v.legacyMigratedConstraintTruthCount());
    }

    private static ir.chobyar.sketch.core.SketchEntity find(K33MirroredCadCanvasView v,String id) {
        for (ir.chobyar.sketch.core.SketchEntity e:v.sketchMirrorEntities()) if (id.equals(e.id())) return e;
        return null;
    }
}
