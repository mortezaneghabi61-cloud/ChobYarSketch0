package ir.chobyar.sketch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Exact user-visible Solid/Form menu copy contract. */
@RunWith(AndroidJUnit4.class)
public final class SolidFormMenuCopyInstrumentationTest {

    @Test public void solidFormMenuUsesProfessionalToolCopy() {
        try(ActivityScenario<ChobYarActivity> scenario=ActivityScenario.launch(ChobYarActivity.class)){
            scenario.onActivity(activity->{
                AdvancedParametricSolidCadCanvasView canvas=new AdvancedParametricSolidCadCanvasView(activity);
                assertArrayEquals(new String[]{
                                "⬆ Extrude / Boolean / Bodies / Face",
                                "⟳ Revolve / Thread",
                                "➜ Sweep / Profile along Path",
                                "≋ Loft / Between Profiles",
                                "⏱ Form History",
                                "↻ Rebuild All Features",
                                "◇ Show 3D"
                        },
                        canvas.solidFormMenuItems(false));
                assertArrayEquals(new String[]{
                                "⬆ Extrude / Boolean / Bodies / Face",
                                "⟳ Revolve / Thread",
                                "➜ Sweep / Profile along Path",
                                "≋ Loft / Between Profiles",
                                "⏱ Form History",
                                "↻ Rebuild All Features",
                                "□ Return to Sketch 2D"
                        },
                        canvas.solidFormMenuItems(true));
                assertEquals(
                        "Form tools create parametric 3D bodies:\nRevolve: profile + axis (selected line or sketch X/Y axis)\nSweep: closed profile + line/polyline path\nLoft: two closed profiles on different sketches/planes",
                        canvas.solidFormMenuMessage()
                );
            });
        }
    }
}
