package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Production contracts for the Figma navigation rail and woodworking DXF export. */
@RunWith(AndroidJUnit4.class)
public final class ProductionNavigationExportInstrumentationTest {

    @Test
    public void figmaRailExposesWorkingZoomInAndOutControls() {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView canvas=findCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                List<TextView> zoomButtons=new ArrayList<>();
                collectZoomButtons(activity.getWindow().getDecorView(),zoomButtons);
                assertEquals("Figma rail must expose zoom + and zoom -",2,zoomButtons.size());

                TextView plus=zoomButtons.get(0).getText().toString().startsWith("+")?zoomButtons.get(0):zoomButtons.get(1);
                TextView minus=plus==zoomButtons.get(0)?zoomButtons.get(1):zoomButtons.get(0);
                float before=canvas.viewScale;
                plus.performClick();
                assertTrue("Zoom + did not increase viewport scale",canvas.viewScale>before);
                minus.performClick();
                assertTrue("Zoom - did not decrease viewport scale",canvas.viewScale<before*1.01f);
            });
        }
    }

    @Test
    public void extrudePreviewStaysCommittableAfterTransientSelectionClear() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(false,"REGION");
        WorkspaceController.State started=controller.begin(WorkspaceController.Tool.EXTRUDE);
        assertTrue("Selected region must start an Extrude preview",started.canCommit());
        controller.previewReady();

        WorkspaceController.State afterDrag=controller.onCanvasState(true,"NONE");
        assertEquals("Extrude tool must remain active",WorkspaceController.Tool.EXTRUDE,afterDrag.tool);
        assertTrue("Changing Extrude height must not disable Done",afterDrag.canCommit());
    }

    @Test
    public void productionSketchExportsMillimeterDxf() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView canvas=findCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.clearAll();
                String result=canvas.executeCommand("RECT 0 0 600 400");
                assertTrue(result,result.contains("Rectangle"));
                String dxf=canvas.buildDxf();
                assertTrue("DXF must declare millimeters",dxf.contains("$INSUNITS\n70\n4"));
                assertTrue("DXF must contain rectangle geometry",dxf.contains("LWPOLYLINE"));
                assertTrue("DXF must be complete",dxf.endsWith("0\nEOF\n"));
            });
        }
    }

    private static void collectZoomButtons(View view,List<TextView> out){
        if(view instanceof TextView){
            String text=((TextView)view).getText().toString();
            if(text.startsWith("+")||text.startsWith("−"))out.add((TextView)view);
        }
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)collectZoomButtons(group.getChildAt(i),out);
        }
    }

    private static Shapr3DGuideCadCanvasView findCanvas(View view){
        if(view instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                Shapr3DGuideCadCanvasView found=findCanvas(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }
}
