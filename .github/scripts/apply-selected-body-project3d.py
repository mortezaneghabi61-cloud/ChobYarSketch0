from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OCCT = ROOT / "app/src/main/java/ir/chobyar/sketch/OcctModelCadCanvasView.java"
GUIDE = ROOT / "app/src/main/java/ir/chobyar/sketch/Shapr3DGuideCadCanvasView.java"
TEST = ROOT / "app/src/androidTest/java/ir/chobyar/sketch/Project3DSelectedBodyInstrumentationTest.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"{label}: anchor not found")
    return text.replace(old, new, 1)


occt = OCCT.read_text(encoding="utf-8")
bridge = '''    /** Exact-body source contract used by Project 3D and 3D guide layers. */
    protected synchronized boolean hasSelectedSolidBody(){
        return selectedBody()!=null;
    }

    protected synchronized long selectedExactNativeHandle(){
        syncNativeHistory(false);
        NativeRecord record=nativeByBody.get(selectedBody());
        return record==null?0L:record.handle;
    }

    protected synchronized List<Long> exactNativeHandlesSnapshot(){
        syncNativeHistory(false);
        List<Long> out=new ArrayList<>();
        for(NativeRecord record:nativeByBody.values()){
            if(record!=null&&record.handle!=0L&&!out.contains(record.handle))out.add(record.handle);
        }
        return out;
    }

'''
anchor = '    /** Snapshot of the exact OCCT display tessellation for the GPU renderer. */\n'
if bridge not in occt:
    if anchor not in occt:
        raise SystemExit("OcctModel selected-body bridge: anchor not found")
    occt = occt.replace(anchor, bridge + anchor, 1)
OCCT.write_text(occt, encoding="utf-8")

guide = GUIDE.read_text(encoding="utf-8")
guide = guide.replace(
    '    private Field viewScaleField,offsetXField,offsetYField,activePlaneField,nativeByBodyField;\n',
    '    private Field viewScaleField,offsetXField,offsetYField,activePlaneField;\n',
    1,
)
guide = guide.replace(
    '            nativeByBodyField=field(OcctModelCadCanvasView.class,"nativeByBody");\n',
    '',
    1,
)

old_project = '''    /** Manual 26.100 Project: exact OCCT Line/Circle/Arc edges -> Construction sketch geometry. */
    public String projectExactBodyEdges(){
        List<double[]> batches=new ArrayList<>();
        for(long handle:nativeHandles()){
            double[] d=NativeBRepKernel.occtEdgeDescriptors(handle);
            if(d!=null&&d.length>=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)batches.add(d);
        }
        if(batches.isEmpty())return "Project 3D • Shape دقیق OCCT آماده نیست";
        return projectDescriptorBatches(batches);
    }
'''
new_project = '''    /** Manual 26.100 Project: selected exact Body when present, otherwise all exact Bodies. */
    public String projectExactBodyEdges(){
        List<Long> handles=projectSourceHandles();
        if(handles.isEmpty())return hasSelectedSolidBody()
                ?"Project 3D • Body انتخاب‌شده Shape دقیق OCCT ندارد"
                :"Project 3D • Shape دقیق OCCT آماده نیست";
        List<double[]> batches=new ArrayList<>();
        for(long handle:handles){
            double[] d=exactProjectDescriptors(handle);
            if(d!=null&&d.length>=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)batches.add(d);
        }
        if(batches.isEmpty())return "Project 3D • لبه دقیق قابل Project پیدا نشد";
        return projectDescriptorBatches(batches);
    }

    protected List<Long> projectSourceHandles(){
        List<Long> out=new ArrayList<>();
        if(hasSelectedSolidBody()){
            long selected=selectedExactNativeHandle();
            if(selected!=0L)out.add(selected);
            return out;
        }
        out.addAll(exactNativeHandlesSnapshot());
        return out;
    }

    protected double[] exactProjectDescriptors(long handle){
        return NativeBRepKernel.occtEdgeDescriptors(handle);
    }
'''
guide = replace_once(guide, old_project, new_project, "Shapr selected-body Project")

old_native = '''    private void pruneCache(){Set<Long> live=new HashSet<>(nativeHandlesRaw());topologyCache.keySet().retainAll(live);}

    private List<Long> nativeHandles(){return nativeHandlesRaw();}
    @SuppressWarnings("unchecked")
    private List<Long> nativeHandlesRaw(){
        List<Long> out=new ArrayList<>();
        try{
            Object v=nativeByBodyField==null?null:nativeByBodyField.get(this);if(!(v instanceof Map))return out;
            for(Object rec:((Map<Object,Object>)v).values()){
                if(rec==null)continue;Field h=findField(rec.getClass(),"handle");if(h!=null){long x=h.getLong(rec);if(x!=0L&&!out.contains(x))out.add(x);}
            }
        }catch(Exception ignored){}
        return out;
    }
'''
new_native = '''    private void pruneCache(){Set<Long> live=new HashSet<>(nativeHandles());topologyCache.keySet().retainAll(live);}

    private List<Long> nativeHandles(){return exactNativeHandlesSnapshot();}
'''
guide = replace_once(guide, old_native, new_native, "Shapr native-handle bridge")
GUIDE.write_text(guide, encoding="utf-8")

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(r'''package ir.chobyar.sketch;

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
    public void noSelectionKeepsAllBodiesBehavior(){
        ProbeCanvas c=create();
        c.selectedBodyPresent=false;
        c.allHandles.addAll(Arrays.asList(11L,22L));
        c.descriptors.put(11L,line(0,0,10,0));
        c.descriptors.put(22L,line(100,0,115,0));

        String result=runProject(c);
        assertEquals(Arrays.asList(11L,22L),c.requested);
        assertTrue(result,result.contains("Line 2"));
        android.util.Log.i("Manual26100Project3D","PROJECT3D_ALL_RESULT requested=2 lines=2 fallback=true");
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
        assertTrue(result,result.contains("Body انتخاب‌شده"));
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
''', encoding="utf-8")

print("Selected-body Project 3D production patch and tests prepared")
