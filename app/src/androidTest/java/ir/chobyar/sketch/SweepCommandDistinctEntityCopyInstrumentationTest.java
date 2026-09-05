package ir.chobyar.sketch;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Exact user-visible SWEEP3D same-entity copy contract. */
@RunWith(AndroidJUnit4.class)
public final class SweepCommandDistinctEntityCopyInstrumentationTest {

    @Test public void sweep3dRequiresDistinctProfileAndPathEntityNumbers() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                Shapr3DGuideCadCanvasView canvas=find(activity.getWindow().getDecorView());
                assertNotNull("Production canvas not found",canvas);
                canvas.clearAll();
                canvas.executeCommand("RECT 0 0 20 10");
                assertEquals(
                        "SWEEP3D — Profile and path must use different entity numbers",
                        canvas.createSweepByEntityIndex(1,1)
                );
            });
        }
    }

    private static Shapr3DGuideCadCanvasView find(View view){
        if(view instanceof Shapr3DGuideCadCanvasView)return(Shapr3DGuideCadCanvasView)view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                Shapr3DGuideCadCanvasView canvas=find(group.getChildAt(i));
                if(canvas!=null)return canvas;
            }
        }
        return null;
    }
}
