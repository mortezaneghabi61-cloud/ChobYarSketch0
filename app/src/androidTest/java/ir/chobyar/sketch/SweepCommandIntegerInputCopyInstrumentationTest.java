package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible SWEEP3D invalid-integer copy contract. */
@RunWith(AndroidJUnit4.class)
public final class SweepCommandIntegerInputCopyInstrumentationTest {

    @Test public void sweep3dRejectsNonIntegerEntityNumbersClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                AdvancedParametricSolidCadCanvasView canvas=new AdvancedParametricSolidCadCanvasView(activity);
                assertEquals(
                        "SWEEP3D — Entity numbers must be integers; Example: SWEEP3D 1 2",
                        canvas.executeCommand("SWEEP3D A B")
                );
            });
        }
    }
}
