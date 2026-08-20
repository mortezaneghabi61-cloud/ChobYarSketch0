from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OCCT = ROOT / "app/src/main/java/ir/chobyar/sketch/OcctModelCadCanvasView.java"
GUIDE = ROOT / "app/src/main/java/ir/chobyar/sketch/Shapr3DGuideCadCanvasView.java"
TEST = ROOT / "app/src/androidTest/java/ir/chobyar/sketch/Project3DSelectedBodyInstrumentationTest.java"


def require(text: str, token: str, label: str) -> None:
    if token not in text:
        raise SystemExit(f"{label}: contract missing")


# The exact Body -> OCCT bridge is already production code. Verify it rather than
# reinserting methods, so this helper stays idempotent after associative Project
# added stable Body IDs and provenance metadata around the same bridge.
occt = OCCT.read_text(encoding="utf-8")
require(occt, "protected synchronized boolean hasSelectedSolidBody()", "selected Body presence")
require(occt, "protected synchronized long selectedExactNativeHandle()", "selected exact handle")
require(occt, "protected synchronized List<Long> exactNativeHandlesSnapshot()", "exact handle snapshot")

# Manual-style Project is selection scoped: an explicit selected Body is required.
# Never silently fall back to every Body in the document.
guide = GUIDE.read_text(encoding="utf-8")
guide = guide.replace(
    "/** Manual 26.100 Project: selected exact Body when present, otherwise all exact Bodies. */",
    "/** Manual 26.100 Project: only the explicitly selected exact Body is projected. */",
    1,
)
old_empty = '''        if(handles.isEmpty())return hasSelectedSolidBody()
                ?"Project 3D • Body انتخاب‌شده Shape دقیق OCCT ندارد"
                :"Project 3D • Shape دقیق OCCT آماده نیست";
'''
new_empty = '''        if(handles.isEmpty())return hasSelectedSolidBody()
                ?"Project 3D • Body انتخاب‌شده Shape دقیق OCCT ندارد"
                :"Project 3D • اول یک Body را انتخاب کن";
'''
if new_empty not in guide:
    if old_empty not in guide:
        raise SystemExit("Project 3D empty-source behavior: anchor not found")
    guide = guide.replace(old_empty, new_empty, 1)

old_sources = '''    protected List<Long> projectSourceHandles(){
        List<Long> out=new ArrayList<>();
        if(hasSelectedSolidBody()){
            long selected=selectedExactNativeHandle();
            if(selected!=0L)out.add(selected);
            return out;
        }
        out.addAll(exactNativeHandlesSnapshot());
        return out;
    }
'''
new_sources = '''    protected List<Long> projectSourceHandles(){
        List<Long> out=new ArrayList<>();
        if(!hasSelectedSolidBody())return out;
        long selected=selectedExactNativeHandle();
        if(selected!=0L)out.add(selected);
        return out;
    }
'''
if new_sources not in guide:
    if old_sources not in guide:
        raise SystemExit("Project 3D selected-only routing: anchor not found")
    guide = guide.replace(old_sources, new_sources, 1)
require(guide, "protected double[] exactProjectDescriptors(long handle)", "Project descriptor seam")
GUIDE.write_text(guide, encoding="utf-8")

# Deterministic x86 emulator contract: prove that selection routes to exactly one
# handle, no selection routes to none, and a selected non-exact Body never falls
# through to another Body.
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
    public void noSelectionProjectsNothingAndRequestsBodySelection(){
        ProbeCanvas c=create();
        c.selectedBodyPresent=false;
        c.allHandles.addAll(Arrays.asList(11L,22L));
        c.descriptors.put(11L,line(0,0,10,0));
        c.descriptors.put(22L,line(100,0,115,0));

        String result=runProject(c);
        assertTrue(c.requested.isEmpty());
        assertTrue(result,result.contains("انتخاب"));
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

print("Selected-only Project 3D production contract and tests prepared")
