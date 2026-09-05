package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible LOFT3D out-of-range entity copy contract. */
@RunWith(AndroidJUnit4.class)
public final class LoftCommandEntityRangeCopyInstrumentationTest {

    @Test public void loft3dReportsOutOfRangeEntityNumbersClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                AdvancedParametricSolidCadCanvasView canvas=new AdvancedParametricSolidCadCanvasView(activity);
                assertEquals(
                        "LOFT3D — Entity numbers are out of range; Available entities: 0",
                        canvas.createLoftByEntityIndex(1,2)
                );
            });
        }
    }
}
