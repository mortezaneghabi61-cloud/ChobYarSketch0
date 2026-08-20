package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ExactFaceTopologyInstrumentationTest {
    private static final int N=NativeBRepKernel.OCCT_FACE_RECORD_SIZE;

    @Test public void planarFaceCaptureUsesExactAreaCenterAndNormal(){
        double[] d=concat(
                plane(0,50,40,0,0,0,0,0,0,-1,8000),
                plane(1,50,40,20,0,0,20,0,0,1,8000));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                d,new Geometry3D.Vec3(45,35,19.9f),"F-top");
        assertNotNull(ref);assertEquals(OcctTopologyRef.FACE,ref.kind);
        assertEquals(NativeBRepKernel.OCCT_FACE_PLANE,ref.signatureKind);
        assertEquals(8000.0,ref.measure,1e-4);
        near(50,ref.anchor.x,.001);near(40,ref.anchor.y,.001);near(20,ref.anchor.z,.001);
        near(1,Math.abs(ref.vector.z),.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_PLANE_CAPTURE area=8000 center=50,40,20 exact=true");
    }

    @Test public void planarFaceReferenceRematchesAfterHistoryDimensionChange(){
        double[] before=concat(
                plane(0,40,30,0,0,0,0,0,0,-1,4800),
                plane(1,40,30,20,0,0,20,0,0,1,4800));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(40,30,20),"F-history");
        assertNotNull(ref);
        double[] rebuilt=concat(
                plane(4,50,35,0,0,0,0,0,0,-1,7000),
                plane(9,50,35,28,0,0,28,0,0,1,7000));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());
        near(50,r.anchor.x,.001);near(35,r.anchor.y,.001);near(28,r.anchor.z,.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_PLANE_REMATCH rebuilt=true center=50,35,28");
    }

    @Test public void cylindricalFaceRematchesByAxisRadiusAndArea(){
        double[] before=concat(
                cylinder(2,0,0,20,0,0,0,0,0,1,10,1256.637061),
                plane(3,0,0,0,0,0,0,0,0,-1,314.159265));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(10,0,20),"F-cylinder");
        assertNotNull(ref);assertEquals(NativeBRepKernel.OCCT_FACE_CYLINDER,ref.signatureKind);
        assertEquals(10.0,ref.secondaryMeasure,1e-4);
        double[] rebuilt=concat(
                cylinder(8,5,4,25,5,4,0,0,0,1,12,1884.955592),
                plane(9,5,4,0,5,4,0,0,0,-1,452.389342));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());
        near(5,r.anchor.x,.001);near(4,r.anchor.y,.001);near(25,r.anchor.z,.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_CYLINDER_REMATCH radius=12 axisZ=true exact=true");
    }

    private static double[] plane(int index,double cx,double cy,double cz,
                                  double ox,double oy,double oz,
                                  double ax,double ay,double az,double area){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_PLANE;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=0;r[13]=1;return r;
    }

    private static double[] cylinder(int index,double cx,double cy,double cz,
                                     double ox,double oy,double oz,
                                     double ax,double ay,double az,
                                     double radius,double area){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_CYLINDER;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=radius;r[13]=1;return r;
    }

    private static double[] concat(double[]... records){
        double[] out=new double[records.length*N];int at=0;
        for(double[] r:records){System.arraycopy(r,0,out,at,N);at+=N;}return out;
    }

    private static void near(double expected,double actual,double eps){
        assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);
    }
}
