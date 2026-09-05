package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible LOFT3D invalid-integer copy contract. */
@RunWith(AndroidJUnit4.class)
public final class LoftCommandIntegerInputCopyInstrumentationTest {

    @Test public void loft3dRejectsNonIntegerEntityNumbersClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                AdvancedParametricSolidCadCanvasView canvas=new AdvancedParametricSolidCadCanvasView(activity);
                assertEquals(
                        "LOFT3D — Entity numbers must be integers; Example: LOFT3D 1 2",
                        canvas.executeCommand("LOFT3D A B")
                );
            });
        }
    }
}
