package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ExactTopologyIndexInstrumentationTest {
    @Test public void edgeHistoryRematchReturnsCurrentTraversalIndex(){
        double[] before=line(4,0,0,0,0,0,40);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdgeDescriptorsForTest(
                before,new Geometry3D.Vec3(0,0,20),null,null,"E-stable");
        assertNotNull(ref);
        double[] rebuilt=concat(
                line(3,20,0,0,20,0,12),
                line(17,5,2,0,5,2,50));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveEdgeDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());assertTrue(r.hasExactIndex());
        assertEquals(17,r.subshapeIndex);
        android.util.Log.i("ExactTopologyIndex","EXACT_EDGE_INDEX_REMATCH old=4 current=17");
    }

    @Test public void faceHistoryRematchReturnsCurrentTraversalIndex(){
        double[] before=plane(6,40,30,20,0,0,20,0,0,1,4800);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(40,30,20),"F-stable");
        assertNotNull(ref);
        double[] rebuilt=concat(
                plane(2,0,0,0,0,0,0,0,0,-1,900),
                plane(23,50,35,28,0,0,28,0,0,1,7000));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());assertTrue(r.hasExactIndex());
        assertEquals(23,r.subshapeIndex);
        android.util.Log.i("ExactTopologyIndex","EXACT_FACE_INDEX_REMATCH old=6 current=23");
    }

    private static double[] line(int index,double x1,double y1,double z1,double x2,double y2,double z2){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];
        r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[1]=index;
        r[2]=x1;r[3]=y1;r[4]=z1;r[5]=x2;r[6]=y2;r[7]=z2;r[17]=1;return r;
    }

    private static double[] plane(int index,double cx,double cy,double cz,
                                  double ox,double oy,double oz,double ax,double ay,double az,double area){
        double[] r=new double[NativeBRepKernel.OCCT_FACE_RECORD_SIZE];
        r[0]=NativeBRepKernel.OCCT_FACE_PLANE;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[13]=1;return r;
    }

    private static double[] concat(double[]... records){
        int total=0;for(double[] r:records)total+=r.length;
        double[] out=new double[total];int at=0;
        for(double[] r:records){System.arraycopy(r,0,out,at,r.length);at+=r.length;}return out;
    }
}
