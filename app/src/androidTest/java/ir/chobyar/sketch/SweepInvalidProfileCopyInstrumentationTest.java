package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible Sweep invalid-profile copy contract on the production canvas. */
@RunWith(AndroidJUnit4.class)
public final class SweepInvalidProfileCopyInstrumentationTest {

    @Test public void sweepReportsInvalidClosedProfileClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                K33MirroredCadCanvasView canvas=new K33MirroredCadCanvasView(activity);
                canvas.clearAll();
                assertEquals("Sweep — Closed profile is invalid or unavailable",canvas.createSweep(null,null));
            });
        }
    }
}
