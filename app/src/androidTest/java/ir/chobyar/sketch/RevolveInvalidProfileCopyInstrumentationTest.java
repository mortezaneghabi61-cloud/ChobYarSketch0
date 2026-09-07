package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible Revolve invalid-profile copy contract on the production canvas. */
@RunWith(AndroidJUnit4.class)
public final class RevolveInvalidProfileCopyInstrumentationTest {

    @Test public void revolveRejectsUnavailableProfileClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                K33MirroredCadCanvasView canvas=new K33MirroredCadCanvasView(activity);
                canvas.clearAll();
                assertEquals(
                        "Revolve — Closed profile is invalid or unavailable",
                        canvas.createRevolve(null,null,false,360f)
                );
            });
        }
    }
}
