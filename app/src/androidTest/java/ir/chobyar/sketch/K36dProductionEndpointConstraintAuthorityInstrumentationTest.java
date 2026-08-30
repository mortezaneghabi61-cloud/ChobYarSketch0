package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class K36dProductionEndpointConstraintAuthorityInstrumentationTest {
    private static final double EPS=1.0e-4;

    @Test public void manualCoincidentUsesClosestSelectedEndpointsStableIdsAndOneUndo() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 10 0"); CadCanvasView.Entity a=cad.selected; String aId=a.stableId();
            cad.executeCommand("LINE 12 2 20 2"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            select(cad,a,b);

            String message=cad.applyManualCoincident();
            assertTrue(message.contains("Coincident"));
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.COINCIDENT,c.kind);
            assertEquals(bId,c.primaryEntityId); assertEquals(0,c.primaryPointIndex);
            assertEquals(aId,c.secondaryEntityId); assertEquals(1,c.secondaryPointIndex);
            assertPoint(modelLine(cad,bId).a,10,0);
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,cad.sketchConstraintCount());
            assertPoint(modelLine(cad,bId).a,12,2);
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1,cad.sketchConstraintCount());
            assertPoint(modelLine(cad,bId).a,10,0);
            return true;
        });
    }

    @Test public void pointOnEntityPropagatesConstrainedMoveUndoRedo() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 20 0"); CadCanvasView.Entity host=cad.selected; String hostId=host.stableId();
            cad.executeCommand("LINE 7 3 7 10"); String ownerId=cad.selected.stableId();

            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    cad.applyModelPointOnEntityForTest(ownerId,0,hostId).code);
            cad.requireSketchMirrorParity();
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY,c.kind);
            assertEquals(ownerId,c.primaryEntityId); assertEquals(0,c.primaryPointIndex);
            assertEquals(hostId,c.secondaryEntityId);
            assertPoint(modelLine(cad,ownerId).a,7,0);
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            select(cad,host);
            cad.moveSelected(0f,4f);
            cad.requireSketchMirrorParity();
            assertPoint(modelLine(cad,ownerId).a,7,4);
            cad.undo();
            cad.requireSketchMirrorParity();
            assertPoint(modelLine(cad,ownerId).a,7,0);
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertPoint(modelLine(cad,ownerId).a,7,4);
            return true;
        });
    }

    @Test public void createSnappedToExistingEndpointCommitsCoincidentWithEntityAsOneUndo() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 100 0"); String hostId=cad.selected.stableId();
            Set<String> before=ids(cad.exportSketchProjectState());

            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad,screen(cad,20f,25f),screen(cad,100.4f,0.2f),true);
            cad.requireSketchMirrorParity();

            String state=cad.exportSketchProjectState();
            String created=onlyNewId(before,state);
            assertEquals(2,entityCount(state));
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.COINCIDENT,c.kind);
            assertEquals(created,c.primaryEntityId); assertEquals(1,c.primaryPointIndex);
            assertEquals(hostId,c.secondaryEntityId); assertEquals(1,c.secondaryPointIndex);
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1,entityCount(cad.exportSketchProjectState()));
            assertEquals(0,cad.sketchConstraintCount());
            assertFalse(ids(cad.exportSketchProjectState()).contains(created));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertTrue(ids(cad.exportSketchProjectState()).contains(created));
            assertEquals(1,cad.sketchConstraintCount());
            return true;
        });
    }

    @Test public void createSnappedToLineInteriorCommitsPointOnEntityWithEntityAsOneUndo() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 100 0"); String hostId=cad.selected.stableId();
            Set<String> before=ids(cad.exportSketchProjectState());

            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad,screen(cad,20f,25f),screen(cad,50.3f,0.2f),true);
            cad.requireSketchMirrorParity();

            String state=cad.exportSketchProjectState();
            String created=onlyNewId(before,state);
            assertEquals("ON_EDGE",cad.sketchLastModelSnapKind());
            assertEquals(1,cad.sketchConstraintCount());
            SketchConstraint c=cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY,c.kind);
            assertEquals(created,c.primaryEntityId); assertEquals(1,c.primaryPointIndex);
            assertEquals(hostId,c.secondaryEntityId); assertEquals(-1,c.secondaryPointIndex);
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1,entityCount(cad.exportSketchProjectState()));
            assertEquals(0,cad.sketchConstraintCount());
            assertFalse(ids(cad.exportSketchProjectState()).contains(created));
            return true;
        });
    }

    @Test public void saveOpenRetainsBothRelationshipsStableIdsAndPointIndexesButResetsSessionHistory() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 20 0"); CadCanvasView.Entity anchor=cad.selected; String anchorId=anchor.stableId();
            cad.executeCommand("LINE 22 2 30 2"); CadCanvasView.Entity coincidentOwner=cad.selected; String coincidentId=coincidentOwner.stableId();
            select(cad,anchor,coincidentOwner);
            assertTrue(cad.applyManualCoincident().contains("Coincident"));

            cad.executeCommand("LINE 0 30 30 30"); String hostId=cad.selected.stableId();
            cad.executeCommand("LINE 9 35 9 45"); String pointOwnerId=cad.selected.stableId();
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    cad.applyModelPointOnEntityForTest(pointOwnerId,0,hostId).code);
            assertEquals(2,cad.sketchConstraintCount());

            SketchConstraint coincident=findKind(cad,SketchConstraint.Kind.COINCIDENT);
            SketchConstraint pointOn=findKind(cad,SketchConstraint.Kind.POINT_ON_ENTITY);
            String raw=cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened=canvas();
            String result=reopened.importSketchProjectState(raw);
            assertFalse(result.toLowerCase().contains("invalid"));
            reopened.requireSketchMirrorParity();
            assertEquals(2,reopened.sketchConstraintCount());
            assertConstraintSame(coincident,reopened,SketchConstraint.Kind.COINCIDENT);
            assertConstraintSame(pointOn,reopened,SketchConstraint.Kind.POINT_ON_ENTITY);
            assertFalse(reopened.sketchAuthorityCanUndo());
            assertFalse(reopened.sketchAuthorityHistoryActive());
            assertEquals(0,reopened.legacyMigratedConstraintTruthCount());
            assertNotNull(modelLine(reopened,anchorId));
            assertNotNull(modelLine(reopened,coincidentId));
            return true;
        });
    }

    @Test public void deletingHostOrOwnerCascadesModelConstraintWithoutLegacyTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView hostDelete=canvas();
            hostDelete.executeCommand("LINE 0 0 20 0"); CadCanvasView.Entity host=hostDelete.selected; String hostId=host.stableId();
            hostDelete.executeCommand("LINE 5 2 5 5"); CadCanvasView.Entity owner=hostDelete.selected; String ownerId=owner.stableId();
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    hostDelete.applyModelPointOnEntityForTest(ownerId,0,hostId).code);
            select(hostDelete,host);
            hostDelete.deleteSelected();
            hostDelete.requireSketchMirrorParity();
            assertEquals(0,hostDelete.sketchConstraintCount());
            assertEquals(0,hostDelete.legacyMigratedConstraintTruthCount());

            K33MirroredCadCanvasView ownerDelete=canvas();
            ownerDelete.executeCommand("LINE 0 0 20 0"); String host2=ownerDelete.selected.stableId();
            ownerDelete.executeCommand("LINE 5 2 5 5"); CadCanvasView.Entity owner2=ownerDelete.selected; String owner2Id=owner2.stableId();
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    ownerDelete.applyModelPointOnEntityForTest(owner2Id,0,host2).code);
            select(ownerDelete,owner2);
            ownerDelete.deleteSelected();
            ownerDelete.requireSketchMirrorParity();
            assertEquals(0,ownerDelete.sketchConstraintCount());
            assertEquals(0,ownerDelete.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void directLegacyEndpointDriftIsReplayedFromModelDuringDrawWithoutChangingModel() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad=canvas();
            cad.executeCommand("LINE 0 0 10 0"); CadCanvasView.Entity a=cad.selected;
            cad.executeCommand("LINE 12 2 20 2"); CadCanvasView.Entity b=cad.selected; String bId=b.stableId();
            select(cad,a,b);
            cad.applyManualCoincident();
            cad.requireSketchMirrorParity();
            SketchGeometry.Line before=modelLine(cad,bId);
            assertPoint(before.a,10,0);

            CadCanvasView.LineEntity legacy=legacyLine(cad,bId);
            legacy.x1=77f; legacy.y1=88f;
            assertEquals(77f,legacy.x1,0f); assertEquals(88f,legacy.y1,0f);

            Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));
            assertEquals(10f,legacy.x1,(float)EPS); assertEquals(0f,legacy.y1,(float)EPS);
            SketchGeometry.Line after=modelLine(cad,bId);
            assertPoint(after.a,before.a.xMm,before.a.yMm);
            assertPoint(after.b,before.b.xMm,before.b.yMm);
            assertEquals(1,cad.sketchConstraintCount());
            assertEquals(0,cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas() {
        Context c=ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView v=new K33MirroredCadCanvasView(c);
        v.clearAll(); return v;
    }

    private static void select(K33MirroredCadCanvasView cad,CadCanvasView.Entity... values){
        cad.selectedObjects.clear();
        for(CadCanvasView.Entity value:values)cad.selectedObjects.add(value);
        cad.selected=values.length==0?null:values[values.length-1];
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad,String id) {
        for(SketchEntity e:cad.sketchMirrorEntities()) if(id.equals(e.id())) return (SketchGeometry.Line)e;
        throw new AssertionError("missing model line "+id);
    }

    private static CadCanvasView.LineEntity legacyLine(K33MirroredCadCanvasView cad,String id){
        for(CadCanvasView.Entity e:cad.entities)
            if(id.equals(e.stableId()) && e instanceof CadCanvasView.LineEntity) return (CadCanvasView.LineEntity)e;
        throw new AssertionError("missing legacy line "+id);
    }

    private static SketchConstraint findKind(K33MirroredCadCanvasView cad,SketchConstraint.Kind kind){
        for(SketchConstraint c:cad.sketchConstraints()) if(c.kind==kind) return c;
        throw new AssertionError("missing constraint "+kind);
    }

    private static void assertConstraintSame(SketchConstraint expected,K33MirroredCadCanvasView cad,SketchConstraint.Kind kind){
        SketchConstraint actual=findKind(cad,kind);
        assertEquals(expected.id,actual.id);
        assertEquals(expected.primaryEntityId,actual.primaryEntityId);
        assertEquals(expected.primaryPointIndex,actual.primaryPointIndex);
        assertEquals(expected.secondaryEntityId,actual.secondaryEntityId);
        assertEquals(expected.secondaryPointIndex,actual.secondaryPointIndex);
    }

    private static void assertPoint(SketchGeometry.Point p,double x,double y){
        assertEquals(x,p.xMm,EPS); assertEquals(y,p.yMm,EPS);
    }

    private static void stroke(K33MirroredCadCanvasView cad,float[] a,float[] b,boolean stylus){
        long down=7_000L;
        send(cad,MotionEvent.ACTION_DOWN,a[0],a[1],down,down,stylus);
        send(cad,MotionEvent.ACTION_MOVE,b[0],b[1],down,down+16L,stylus);
        send(cad,MotionEvent.ACTION_UP,b[0],b[1],down,down+32L,stylus);
    }

    private static void send(K33MirroredCadCanvasView cad,int action,float x,float y,long down,long time,boolean stylus){
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);
        if(stylus)e.setSource(InputDevice.SOURCE_STYLUS);
        cad.onTouchEvent(e); e.recycle();
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
        for(int i=0;i<rows.length();i++) out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static String onlyNewId(Set<String> before,String raw)throws Exception{
        Set<String> after=ids(raw); after.removeAll(before);
        assertEquals(1,after.size()); return after.iterator().next();
    }

    private static <T>T onMain(Callable<T> callable)throws Exception{
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
