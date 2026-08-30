package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
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
            K33MirroredCadCanvasView cad=cad();
            cad.executeCommand("LINE 0 0 100 12");
            String id=cad.selected.stableId();

            String out=cad.applyHorizontalVerticalConstraint();
            assertTrue(out.contains("H/V"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());
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

    @Test public void multiLineHorizontalVerticalUsesNearestAxisAndSurvivesProjectReopen() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=cad();
            cad.executeCommand("LINE 0 0 100 8"); CadCanvasView.Entity a=cad.selected; String aId=a.stableId();
            cad.executeCommand("LINE 20 10 24 120"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            cad.executeCommand("LINE 40 50 130 46"); CadCanvasView.Entity c=cad.selected; String cId=c.stableId();
            select(cad,a,b,c);

            assertTrue(cad.applyHorizontalVerticalConstraint().contains("H/V"));
            cad.requireSketchMirrorParity();
            assertEquals(3,cad.sketchConstraintCount());
            assertEquals(SketchConstraint.Kind.HORIZONTAL,constraintFor(cad,aId).kind);
            assertEquals(SketchConstraint.Kind.VERTICAL,constraintFor(cad,bId).kind);
            assertEquals(SketchConstraint.Kind.HORIZONTAL,constraintFor(cad,cId).kind);
            assertHorizontal(modelLine(cad,aId));
            assertVertical(modelLine(cad,bId));
            assertHorizontal(modelLine(cad,cId));
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            String raw=cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened=cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();
            assertEquals(3,reopened.sketchConstraintCount());
            assertHorizontal(modelLine(reopened,aId));
            assertVertical(modelLine(reopened,bId));
            assertHorizontal(modelLine(reopened,cId));
            return true;
        });
    }

    @Test public void parallelSupportsThreeLinesConstrainedMoveUndoAndPersistence() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=cad();
            cad.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity a=cad.selected; String aId=a.stableId();
            cad.executeCommand("LINE 20 50 95 82"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            cad.executeCommand("LINE -30 90 50 130"); CadCanvasView.Entity c=cad.selected; String cId=c.stableId();
            select(cad,a,b,c);

            assertTrue(cad.applyParallelConstraint().contains("Parallel"));
            cad.requireSketchMirrorParity();
            assertEquals(2,cad.sketchConstraintCount());
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));
            assertParallel(modelLine(cad,aId),modelLine(cad,cId));
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            select(cad,b);
            cad.moveSelected(17f,9f);
            cad.requireSketchMirrorParity();
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));
            assertParallel(modelLine(cad,aId),modelLine(cad,cId));
            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(2,cad.sketchConstraintCount());
            assertParallel(modelLine(cad,aId),modelLine(cad,bId));

            String raw=cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened=cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();
            assertEquals(2,reopened.sketchConstraintCount());
            assertParallel(modelLine(reopened,aId),modelLine(reopened,bId));
            assertParallel(modelLine(reopened,aId),modelLine(reopened,cId));
            return true;
        });
    }

    @Test public void parallelAnchorPolicyMakesFirstOrLastSelectedDeterministic() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView first=cad();
            first.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity a=first.selected; String aId=a.stableId();
            first.executeCommand("LINE 20 50 95 82"); CadCanvasView.Entity b=first.selected; String bId=b.stableId();
            SketchGeometry.Line aBefore=modelLine(first,aId);
            select(first,a,b);
            first.setConstraintAnchorPolicy(K33MirroredCadCanvasView.ConstraintAnchorPolicy.FIRST_SELECTED);
            first.applyParallelConstraint();
            assertLineSame(aBefore,modelLine(first,aId));
            assertParallel(modelLine(first,aId),modelLine(first,bId));

            K33MirroredCadCanvasView last=cad();
            last.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity x=last.selected; String xId=x.stableId();
            last.executeCommand("LINE 20 50 95 82"); CadCanvasView.Entity y=last.selected; String yId=y.stableId();
            SketchGeometry.Line yBefore=modelLine(last,yId);
            select(last,x,y);
            last.setConstraintAnchorPolicy(K33MirroredCadCanvasView.ConstraintAnchorPolicy.LAST_SELECTED);
            last.applyParallelConstraint();
            assertLineSame(yBefore,modelLine(last,yId));
            assertParallel(modelLine(last,xId),modelLine(last,yId));
            return true;
        });
    }

    @Test public void disconnectedPerpendicularKeepsCentersAndSurvivesEditUndoRedoAndReopen() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=cad();
            cad.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity a=cad.selected; String aId=a.stableId();
            cad.executeCommand("LINE 240 70 300 110"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            SketchGeometry.Point aCenter=center(modelLine(cad,aId));
            SketchGeometry.Point bCenter=center(modelLine(cad,bId));
            select(cad,a,b);

            assertTrue(cad.applyPerpendicularConstraint().contains("Perpendicular"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());
            assertTrue(cad.modelConstraintFeedbackCount()>0);
            assertPerpendicular(modelLine(cad,aId),modelLine(cad,bId));
            assertPointSame(aCenter,center(modelLine(cad,aId)));
            assertPointSame(bCenter,center(modelLine(cad,bId)));

            select(cad,b);
            cad.moveSelected(15f,-9f);
            cad.requireSketchMirrorParity();
            assertPerpendicular(modelLine(cad,aId),modelLine(cad,bId));
            cad.undo();
            cad.requireSketchMirrorParity();
            assertPerpendicular(modelLine(cad,aId),modelLine(cad,bId));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertPerpendicular(modelLine(cad,aId),modelLine(cad,bId));

            String raw=cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened=cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();
            assertEquals(1,reopened.sketchConstraintCount());
            assertPerpendicular(modelLine(reopened,aId),modelLine(reopened,bId));
            return true;
        });
    }

    @Test public void perpendicularAnchorPolicyHonorsSelectionOrder() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView first=cad();
            first.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity a=first.selected; String aId=a.stableId();
            first.executeCommand("LINE 200 40 260 80"); CadCanvasView.Entity b=first.selected; String bId=b.stableId();
            SketchGeometry.Line aBefore=modelLine(first,aId);
            select(first,a,b);
            first.setConstraintAnchorPolicy(K33MirroredCadCanvasView.ConstraintAnchorPolicy.FIRST_SELECTED);
            first.applyPerpendicularConstraint();
            assertLineSame(aBefore,modelLine(first,aId));
            assertPerpendicular(modelLine(first,aId),modelLine(first,bId));

            K33MirroredCadCanvasView last=cad();
            last.executeCommand("LINE 0 0 100 0"); CadCanvasView.Entity x=last.selected; String xId=x.stableId();
            last.executeCommand("LINE 200 40 260 80"); CadCanvasView.Entity y=last.selected; String yId=y.stableId();
            SketchGeometry.Line yBefore=modelLine(last,yId);
            select(last,x,y);
            last.setConstraintAnchorPolicy(K33MirroredCadCanvasView.ConstraintAnchorPolicy.LAST_SELECTED);
            last.applyPerpendicularConstraint();
            assertLineSame(yBefore,modelLine(last,yId));
            assertPerpendicular(modelLine(last,xId),modelLine(last,yId));
            return true;
        });
    }

    @Test public void conflictingConstraintFailsAtomicallyWithoutLegacyTruthOrHistoryDamage() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=cad();
            cad.executeCommand("LINE 0 0 100 2"); CadCanvasView.Entity a=cad.selected; String aId=a.stableId();
            cad.executeCommand("LINE 0 40 100 43"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            select(cad,a,b);
            cad.applyHorizontalVerticalConstraint();
            assertEquals(2,cad.sketchConstraintCount());
            SketchGeometry.Line aBefore=modelLine(cad,aId);
            SketchGeometry.Line bBefore=modelLine(cad,bId);

            select(cad,a,b);
            String out=cad.applyPerpendicularConstraint();
            assertTrue(out.contains("unchanged"));
            cad.requireSketchMirrorParity();
            assertEquals(2,cad.sketchConstraintCount());
            assertLineSame(aBefore,modelLine(cad,aId));
            assertLineSame(bBefore,modelLine(cad,bId));
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad(){
        Context context=ApplicationProvider.getApplicationContext();
        return new K33MirroredCadCanvasView(context);
    }

    private static void select(K33MirroredCadCanvasView cad,CadCanvasView.Entity... values){
        cad.selectedObjects.clear();
        for(CadCanvasView.Entity value:values)cad.selectedObjects.add(value);
        cad.selected=values.length==0?null:values[values.length-1];
    }

    private static SketchConstraint constraintFor(K33MirroredCadCanvasView cad,String entityId){
        for(SketchConstraint c:cad.sketchConstraints())if(c.primaryEntityId.equals(entityId))return c;
        throw new AssertionError("missing constraint for "+entityId);
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad,String id){
        List<SketchEntity> entities=cad.sketchMirrorEntities();
        for(SketchEntity entity:entities){
            if(id.equals(entity.id()))return (SketchGeometry.Line)entity;
        }
        throw new AssertionError("missing model line "+id);
    }

    private static SketchGeometry.Point center(SketchGeometry.Line line){
        return new SketchGeometry.Point((line.a.xMm+line.b.xMm)*0.5,(line.a.yMm+line.b.yMm)*0.5);
    }

    private static void assertHorizontal(SketchGeometry.Line a){ assertEquals(a.a.yMm,a.b.yMm,EPS); }
    private static void assertVertical(SketchGeometry.Line a){ assertEquals(a.a.xMm,a.b.xMm,EPS); }

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

    private static void assertLineSame(SketchGeometry.Line expected,SketchGeometry.Line actual){
        assertEquals(expected.a.xMm,actual.a.xMm,EPS);
        assertEquals(expected.a.yMm,actual.a.yMm,EPS);
        assertEquals(expected.b.xMm,actual.b.xMm,EPS);
        assertEquals(expected.b.yMm,actual.b.yMm,EPS);
    }

    private static void assertPointSame(SketchGeometry.Point expected,SketchGeometry.Point actual){
        assertEquals(expected.xMm,actual.xMm,EPS);
        assertEquals(expected.yMm,actual.yMm,EPS);
    }

    private static <T>T onMain(Callable<T> callable) throws Exception{
        FutureTask<T> task=new FutureTask<>(callable);
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(task);
        return task.get();
    }
}
