package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class AssociativeProjectProvenanceInstrumentationTest {

    @Test
    public void provenanceSurvivesSketchUndoRedoWithoutResurrection(){
        ProbeCanvas c=create();
        c.bodyId=7;c.handle=700L;c.byHandle.put(700L,line(9,0,0,10,0));
        String created=command(c,"PROJECTREF");
        assertTrue(created,created.contains("Line 1"));
        assertEquals(1,c.associativeProjectEntityCountForTest());
        CadCanvasView.Entity e=c.firstAssociativeProjectEntityForTest();
        assertNotNull(e);assertTrue(e.isConstruction());
        assertEquals(7,e.getReferenceBodyId());assertEquals(9,e.getReferenceEdgeIndex());assertEquals(NativeBRepKernel.OCCT_EDGE_LINE,e.getReferenceEdgeKind());

        undo(c);
        assertEquals(0,c.associativeProjectEntityCountForTest());
        String refresh=refresh(c);
        assertTrue(refresh,refresh.contains("0 Reference"));
        assertEquals(0,c.associativeProjectEntityCountForTest());

        assertTrue(redoSketch(c));
        assertEquals(1,c.associativeProjectEntityCountForTest());
        CadCanvasView.Entity restored=c.firstAssociativeProjectEntityForTest();
        assertEquals(7,restored.getReferenceBodyId());assertEquals(9,restored.getReferenceEdgeIndex());
        android.util.Log.i("AssocProject","ASSOC_UNDO_REDO_RESULT created=1 undo=0 refresh=0 redo=1 body=7 edge=9");
    }

    @Test
    public void refreshMutatesExistingEntityAndDeleteNeverResurrectsIt(){
        ProbeCanvas c=create();
        c.bodyId=8;c.handle=800L;c.byHandle.put(800L,line(4,0,0,10,0));
        command(c,"PROJECTREF");
        CadCanvasView.Entity before=c.firstAssociativeProjectEntityForTest();assertNotNull(before);
        c.byHandle.put(800L,line(4,0,0,25,0));
        String r=refresh(c);assertTrue(r,r.contains("Updated 1"));
        CadCanvasView.Entity after=c.firstAssociativeProjectEntityForTest();assertSame(before,after);
        RectF b=after.bounds();assertEquals(25f,b.width(),0.01f);

        deleteFirst(c);assertEquals(0,c.associativeProjectEntityCountForTest());
        String noRes=refresh(c);assertTrue(noRes,noRes.contains("0 Reference"));assertEquals(0,c.associativeProjectEntityCountForTest());
        undo(c);assertEquals(1,c.associativeProjectEntityCountForTest());
        assertEquals(25f,c.firstAssociativeProjectEntityForTest().bounds().width(),0.01f);
        android.util.Log.i("AssocProject","ASSOC_DELETE_RESULT updated=25 sameObject=true delete=0 refresh=0 undoDelete=1");
    }

    @Test
    public void missingEdgeAndKindMismatchAreGuardedWithoutGeometryReplacement(){
        ProbeCanvas c=create();
        c.bodyId=9;c.handle=900L;c.byHandle.put(900L,line(5,0,0,12,0));command(c,"PROJECTREF");
        CadCanvasView.Entity e=c.firstAssociativeProjectEntityForTest();float width=e.bounds().width();

        c.byHandle.put(900L,line(6,0,0,50,0));
        String missing=refresh(c);assertTrue(missing,missing.contains("EdgeMissing 1"));assertSame(e,c.firstAssociativeProjectEntityForTest());assertEquals(width,e.bounds().width(),0.01f);

        c.byHandle.put(900L,circle(5,0,0,0,30));
        String mismatch=refresh(c);assertTrue(mismatch,mismatch.contains("TypeMismatch 1"));assertSame(e,c.firstAssociativeProjectEntityForTest());assertEquals(width,e.bounds().width(),0.01f);

        c.handle=0L;
        String bodyMissing=refresh(c);assertTrue(bodyMissing,bodyMissing.contains("BodyMissing 1"));assertEquals(1,c.associativeProjectEntityCountForTest());
        android.util.Log.i("AssocProject","ASSOC_GUARD_RESULT edgeMissing=1 typeMismatch=1 bodyMissing=1 preserved=true");
    }

    @Test
    public void lineCircleAndArcReferencesRefreshByStableEdgeIndex(){
        ProbeCanvas c=create();
        c.bodyId=11;c.handle=1100L;
        c.byHandle.put(1100L,concat(line(1,0,0,10,0),circle(2,30,20,0,5),arc(3,60,20,0,8,0,Math.PI/2)));
        String created=command(c,"PROJECTREF");
        assertTrue(created,created.contains("Line 1")&&created.contains("Circle 1")&&created.contains("Arc 1"));
        assertEquals(3,c.associativeProjectEntityCountForTest());

        c.byHandle.put(1100L,concat(line(1,0,0,18,0),circle(2,30,20,0,9),arc(3,60,20,0,12,0,Math.PI)));
        String updated=refresh(c);assertTrue(updated,updated.contains("Updated 3"));
        CadCanvasView.Entity line=c.findReference(1),circle=c.findReference(2),arc=c.findReference(3);
        assertNotNull(line);assertNotNull(circle);assertNotNull(arc);
        assertEquals(18f,line.bounds().width(),0.01f);
        assertEquals(18f,circle.bounds().width(),0.01f);
        assertEquals(24f,arc.bounds().width(),0.01f);
        assertEquals(NativeBRepKernel.OCCT_EDGE_LINE,line.getReferenceEdgeKind());
        assertEquals(NativeBRepKernel.OCCT_EDGE_CIRCLE,circle.getReferenceEdgeKind());
        assertEquals(NativeBRepKernel.OCCT_EDGE_ARC,arc.getReferenceEdgeKind());
        android.util.Log.i("AssocProject","ASSOC_MULTI_RESULT refs=3 updated=3 line=18 circleD=18 arcD=24 body=11");
    }

    private static ProbeCanvas create(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ProbeCanvas[] out=new ProbeCanvas[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=new ProbeCanvas(context));
        return out[0];
    }

    private static String command(ProbeCanvas c,String command){final String[] out=new String[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=c.executeCommand(command));return out[0];}
    private static String refresh(ProbeCanvas c){final String[] out=new String[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=c.refreshAssociativeProjectReferences());return out[0];}
    private static void undo(ProbeCanvas c){InstrumentationRegistry.getInstrumentation().runOnMainSync(c::undo);}
    private static boolean redoSketch(ProbeCanvas c){final boolean[] out=new boolean[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=c.redoSketch());return out[0];}
    private static void deleteFirst(ProbeCanvas c){InstrumentationRegistry.getInstrumentation().runOnMainSync(c::deleteFirstReference);}

    private static double[] line(int edge,double x1,double y1,double x2,double y2){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[1]=edge;r[2]=x1;r[3]=y1;r[4]=0;r[5]=x2;r[6]=y2;r[7]=0;return r;
    }
    private static double[] circle(int edge,double cx,double cy,double cz,double radius){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];r[0]=NativeBRepKernel.OCCT_EDGE_CIRCLE;r[1]=edge;r[2]=cx+radius;r[3]=cy;r[4]=cz;r[5]=cx+radius;r[6]=cy;r[7]=cz;r[8]=cx;r[9]=cy;r[10]=cz;r[11]=0;r[12]=0;r[13]=1;r[14]=radius;r[15]=0;r[16]=Math.PI*2;r[17]=1;return r;
    }
    private static double[] arc(int edge,double cx,double cy,double cz,double radius,double first,double last){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];r[0]=NativeBRepKernel.OCCT_EDGE_ARC;r[1]=edge;r[2]=cx+Math.cos(first)*radius;r[3]=cy+Math.sin(first)*radius;r[4]=cz;r[5]=cx+Math.cos(last)*radius;r[6]=cy+Math.sin(last)*radius;r[7]=cz;r[8]=cx;r[9]=cy;r[10]=cz;r[11]=0;r[12]=0;r[13]=1;r[14]=radius;r[15]=first;r[16]=last;r[17]=1;return r;
    }
    private static double[] concat(double[]... records){int n=0;for(double[] r:records)n+=r.length;double[] out=new double[n];int at=0;for(double[] r:records){System.arraycopy(r,0,out,at,r.length);at+=r.length;}return out;}

    private static final class ProbeCanvas extends Shapr3DGuideCadCanvasView{
        int bodyId=-1;long handle=0L;final Map<Long,double[]> byHandle=new HashMap<>();
        ProbeCanvas(Context context){super(context);}
        @Override protected synchronized boolean hasSelectedSolidBody(){return bodyId>=0;}
        @Override protected synchronized int selectedExactBodyId(){return bodyId;}
        @Override protected synchronized long selectedExactNativeHandle(){return handle;}
        @Override protected synchronized long exactNativeHandleForBodyId(int id){return id==bodyId?handle:0L;}
        @Override protected double[] exactProjectDescriptors(long h){double[] d=byHandle.get(h);return d==null?new double[0]:d;}
        CadCanvasView.Entity findReference(int edge){for(CadCanvasView.Entity e:coreAssociativeReferenceSnapshot())if(e.getReferenceEdgeIndex()==edge)return e;return null;}
        void deleteFirstReference(){CadCanvasView.Entity e=firstAssociativeProjectEntityForTest();if(e==null)return;selected=e;executeCommand("DELETE");}
    }
}
