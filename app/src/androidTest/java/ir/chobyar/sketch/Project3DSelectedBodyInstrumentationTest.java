package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class Project3DSelectedBodyInstrumentationTest {

    @Test
    public void selectedBodyProjectsOnlyItsExactHandle(){
        ProbeCanvas c=create();
        c.selectedBodyPresent=true;
        c.selectedHandle=22L;
        c.allHandles.addAll(Arrays.asList(11L,22L));
        c.descriptors.put(11L,line(0,0,10,0));
        c.descriptors.put(22L,line(100,0,115,0));

        String result=runProject(c);
        assertEquals(Arrays.asList(22L),c.requested);
        assertTrue(result,result.contains("Line 1"));
        android.util.Log.i("Manual26100Project3D","PROJECT3D_SELECTED_RESULT requested=22 lines=1 fallback=false");
    }

    @Test
    public void noSelectionProjectsNothingAndRequestsBodySelection(){
        ProbeCanvas c=create();
        c.selectedBodyPresent=false;
        c.allHandles.addAll(Arrays.asList(11L,22L));
        c.descriptors.put(11L,line(0,0,10,0));
        c.descriptors.put(22L,line(100,0,115,0));

        String result=runProject(c);
        assertTrue(c.requested.isEmpty());
        assertTrue(result,result.contains("Select a body first"));
        android.util.Log.i("Manual26100Project3D","PROJECT3D_NO_SELECTION_RESULT requested=0 guarded=true");
    }

    @Test
    public void selectedBodyWithoutExactShapeDoesNotProjectOtherBodies(){
        ProbeCanvas c=create();
        c.selectedBodyPresent=true;
        c.selectedHandle=0L;
        c.allHandles.add(11L);
        c.descriptors.put(11L,line(0,0,10,0));

        String result=runProject(c);
        assertTrue(c.requested.isEmpty());
        assertTrue(result,result.contains("Body selected"));
        android.util.Log.i("Manual26100Project3D","PROJECT3D_NO_EXACT_RESULT requested=0 guarded=true");
    }

    private static ProbeCanvas create(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ProbeCanvas[] out=new ProbeCanvas[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=new ProbeCanvas(context));
        return out[0];
    }

    private static String runProject(ProbeCanvas c){
        final String[] out=new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->out[0]=c.projectExactBodyEdges());
        return out[0];
    }

    private static double[] line(double x1,double y1,double x2,double y2){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];
        r[0]=NativeBRepKernel.OCCT_EDGE_LINE;
        r[2]=x1;r[3]=y1;r[4]=0;
        r[5]=x2;r[6]=y2;r[7]=0;
        return r;
    }

    private static final class ProbeCanvas extends Shapr3DGuideCadCanvasView {
        boolean selectedBodyPresent;
        long selectedHandle;
        final List<Long> allHandles=new ArrayList<>();
        final List<Long> requested=new ArrayList<>();
        final Map<Long,double[]> descriptors=new HashMap<>();

        ProbeCanvas(Context context){super(context);}

        @Override protected synchronized boolean hasSelectedSolidBody(){return selectedBodyPresent;}
        @Override protected synchronized long selectedExactNativeHandle(){return selectedHandle;}
        @Override protected synchronized List<Long> exactNativeHandlesSnapshot(){return new ArrayList<>(allHandles);}
        @Override protected double[] exactProjectDescriptors(long handle){
            requested.add(handle);
            double[] d=descriptors.get(handle);
            return d==null?new double[0]:d;
        }
    }
}
