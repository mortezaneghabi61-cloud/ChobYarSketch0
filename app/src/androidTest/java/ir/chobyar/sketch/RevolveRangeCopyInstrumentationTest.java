package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Exact user-visible Revolve range-validation copy contract on the production canvas. */
@RunWith(AndroidJUnit4.class)
public final class RevolveRangeCopyInstrumentationTest {

    @Test public void revolveReportsAngleAndHeightRangeClearly() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                K33MirroredCadCanvasView canvas=new K33MirroredCadCanvasView(activity);
                canvas.clearAll();
                String expected="Revolve — Angle and height must be finite; |angle| 0.01°–36000°, height ±100000 mm";
                assertEquals(expected,canvas.createRevolve(null,null,false,0f,0f));
                assertEquals(expected,canvas.createRevolve(null,null,false,360f,100001f));
            });
        }
    }
}
