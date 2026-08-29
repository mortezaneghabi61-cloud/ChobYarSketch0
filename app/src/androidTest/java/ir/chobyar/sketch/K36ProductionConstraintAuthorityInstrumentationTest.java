package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

@RunWith(AndroidJUnit4.class)
public class K36ProductionConstraintAuthorityInstrumentationTest {
    private static final double EPS=1.0e-4;

    @Test public void horizontalConstraintIsModelOwnedAndUndoRedoRestoresConstraintAndGeometry() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("LINE 0 0 100 12");
            String id=cad.selected.stableId();

            String out=cad.applyHorizontalVerticalConstraint();
            assertTrue(out.contains("H/V"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.HORIZONTAL,c.kind);
            assertEquals(id,c.primaryEntityId);
            assertHorizontal(modelLine(cad,id));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,cad.sketchConstraintCount());
            SketchGeometry.Line original=modelLine(cad,id);
            assertEquals(0.0,original.a.yMm,EPS);
            assertEquals(12.0,original.b.yMm,EPS);

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertHorizontal(modelLine(cad,id));
            return true;
        });
    }

    @Test public void parallelConstraintUsesStableIdsAndSurvivesConstrainedMoveHistory() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.Entity a=cad.selected;
            String aId=a.stableId();
            cad.executeCommand("LINE 20 50 95 82");
            CadCanvasView.Entity b=cad.selected;
            String bId=b.stableId();
            select(cad,a,b);

            String out=cad.applyParallelConstraint();
            assertTrue(out.contains("Parallel"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.PARALLEL,c.kind);
            assertEquals(aId,c.primaryEntityId);
            assertEquals(bId,c.secondaryEntityId);
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));

            select(cad,b);
            cad.moveSelected(17f,9f);
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));
            return true;
        });
    }

    @Test public void perpendicularConstraintIsSolvedModelFirstAndLegacyMatchesExactly() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.Entity a=cad.selected;
            String aId=a.stableId();
            cad.executeCommand("LINE 50 20 95 85");
            CadCanvasView.Entity b=cad.selected;
            String bId=b.stableId();
            select(cad,a,b);

            String out=cad.applyPerpendicularConstraint();
            assertTrue(out.contains("Perpendicular"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.PERPENDICULAR,c.kind);
            assertPerpendicular(modelLine(cad,aId),modelLine(cad,bId));
            return true;
        });
    }

    private static void select(K33MirroredCadCanvasView cad,CadCanvasView.Entity... values){
        cad.selectedObjects.clear();
        for(CadCanvasView.Entity value:values)cad.selectedObjects.add(value);
        cad.selected=values.length==0?null:values[values.length-1];
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad,String id){
        List<SketchEntity> entities=cad.sketchMirrorEntities();
        for(SketchEntity entity:entities){
            if(id.equals(entity.id()))return (SketchGeometry.Line)entity;
        }
        throw new AssertionError("missing model line "+id);
    }

    private static void assertHorizontal(SketchGeometry.Line a){
        assertEquals(a.a.yMm,a.b.yMm,EPS);
    }

    private static void assertParallel(SketchGeometry.Line a,SketchGeometry.Line b){
        double ax=a.b.xMm-a.a.xMm, ay=a.b.yMm-a.a.yMm;
        double bx=b.b.xMm-b.a.xMm, by=b.b.yMm-b.a.yMm;
        double denom=Math.hypot(ax,ay)*Math.hypot(bx,by);
        assertEquals(0.0,(ax*by-ay*bx)/denom,EPS);
    }

    private static void assertPerpendicular(SketchGeometry.Line a,SketchGeometry.Line b){
        double ax=a.b.xMm-a.a.xMm, ay=a.b.yMm-a.a.yMm;
        double bx=b.b.xMm-b.a.xMm, by=b.b.yMm-b.a.yMm;
        double denom=Math.hypot(ax,ay)*Math.hypot(bx,by);
        assertEquals(0.0,(ax*bx+ay*by)/denom,EPS);
    }

    private static <T>T onMain(Callable<T> callable) throws Exception{
        FutureTask<T> task=new FutureTask<>(callable);
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(task);
        return task.get();
    }
}
