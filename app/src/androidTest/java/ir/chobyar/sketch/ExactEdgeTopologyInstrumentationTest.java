package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ExactEdgeTopologyInstrumentationTest {
    private static final int N=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;

    @Test public void exactLineCaptureUsesAnalyticDescriptorInsteadOfMeshSegments(){
        double[] descriptors=concat(
                line(1,0,0,0,100,0,0),
                line(2,0,50,0,60,50,0));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdgeDescriptorsForTest(
                descriptors,new Geometry3D.Vec3(40,.5f,0),
                new Geometry3D.Vec3(0,0,0),new Geometry3D.Vec3(100,0,0),"E-line");
        assertNotNull(ref);
        assertEquals(OcctTopologyRef.EDGE,ref.kind);
        assertEquals("E-line",ref.id);
        assertEquals(100.0,ref.measure,1e-4);
        near(50,ref.anchor.x,.001);near(0,ref.anchor.y,.001);near(0,ref.anchor.z,.001);
        assertTrue(Math.abs(ref.vector.x)>.999f);
        android.util.Log.i("ExactTopology","EXACT_EDGE_LINE_RESULT measure=100 anchor=50,0,0 analytic=true");
    }

    @Test public void fullCircleReferenceRematchesAfterHistoryRadiusAndPositionChange(){
        double[] before=circle(3,20,30,5,0,0,1,10);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdgeDescriptorsForTest(
                before,new Geometry3D.Vec3(20,40,5),null,null,"E-circle");
        assertNotNull(ref);
        assertEquals(2.0*Math.PI*10.0,ref.measure,1e-4);
        near(20,ref.anchor.x,.001);near(30,ref.anchor.y,.001);near(5,ref.anchor.z,.001);
        near(1,Math.abs(ref.vector.z),.001);

        double[] rebuilt=circle(7,25,35,5,0,0,1,12);
        OcctTopologyRef.Resolution resolution=OcctTopologyRef.resolveEdgeDescriptorsForTest(rebuilt,ref);
        assertNotNull(resolution);assertTrue(resolution.confident());
        near(25,resolution.anchor.x,.001);near(35,resolution.anchor.y,.001);near(5,resolution.anchor.z,.001);
        android.util.Log.i("ExactTopology","EXACT_EDGE_CIRCLE_RESULT rematch=true oldR=10 newR=12 center=25,35,5");
    }

    @Test public void arcCaptureUsesTheActualAnalyticSpan(){
        double[] descriptors=concat(
                arc(4,0,0,0,10,0,Math.PI),
                arc(5,0,0,0,10,Math.PI,Math.PI*2.0));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdgeDescriptorsForTest(
                descriptors,new Geometry3D.Vec3(0,-10,0),null,null,"E-lower-arc");
        assertNotNull(ref);
        assertEquals(Math.PI*10.0,ref.measure,1e-4);
        near(0,ref.anchor.x,.002);near(-10,ref.anchor.y,.002);near(0,ref.anchor.z,.002);

        double[] rebuilt=arc(9,5,0,0,14,Math.PI,Math.PI*2.0);
        OcctTopologyRef.Resolution resolution=OcctTopologyRef.resolveEdgeDescriptorsForTest(rebuilt,ref);
        assertNotNull(resolution);assertTrue(resolution.confident());
        near(5,resolution.anchor.x,.003);near(-14,resolution.anchor.y,.003);
        android.util.Log.i("ExactTopology","EXACT_EDGE_ARC_RESULT span=180 rematch=true analytic=true");
    }

    private static double[] line(int index,double x1,double y1,double z1,double x2,double y2,double z2){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[1]=index;
        r[2]=x1;r[3]=y1;r[4]=z1;r[5]=x2;r[6]=y2;r[7]=z2;r[15]=0;r[16]=1;r[17]=1;return r;
    }

    private static double[] circle(int index,double cx,double cy,double cz,double nx,double ny,double nz,double radius){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_CIRCLE;r[1]=index;
        r[2]=cx+radius;r[3]=cy;r[4]=cz;r[5]=cx+radius;r[6]=cy;r[7]=cz;
        r[8]=cx;r[9]=cy;r[10]=cz;r[11]=nx;r[12]=ny;r[13]=nz;r[14]=radius;r[15]=0;r[16]=Math.PI*2.0;r[17]=1;return r;
    }

    private static double[] arc(int index,double cx,double cy,double cz,double radius,double first,double last){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_EDGE_ARC;r[1]=index;
        r[2]=cx+Math.cos(first)*radius;r[3]=cy+Math.sin(first)*radius;r[4]=cz;
        r[5]=cx+Math.cos(last)*radius;r[6]=cy+Math.sin(last)*radius;r[7]=cz;
        r[8]=cx;r[9]=cy;r[10]=cz;r[11]=0;r[12]=0;r[13]=1;r[14]=radius;r[15]=first;r[16]=last;r[17]=1;return r;
    }

    private static double[] concat(double[]... records){
        double[] out=new double[records.length*N];int at=0;
        for(double[] r:records){System.arraycopy(r,0,out,at,N);at+=N;}return out;
    }

    private static void near(double expected,double actual,double eps){
        assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);
    }
}
