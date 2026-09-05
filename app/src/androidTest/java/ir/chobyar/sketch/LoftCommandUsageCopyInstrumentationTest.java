package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible LOFT3D usage/help copy contract. */
@RunWith(AndroidJUnit4.class)
public final class LoftCommandUsageCopyInstrumentationTest {

    @Test public void loft3dUsageNamesExample() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                AdvancedParametricSolidCadCanvasView canvas=new AdvancedParametricSolidCadCanvasView(activity);
                assertEquals(
                        "LOFT3D — Two profile numbers are required; Example: LOFT3D 1 2",
                        canvas.executeCommand("LOFT3D")
                );
            });
        }
    }
}
