package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/** Ensures a creation command replaces stale multi-selection with the new entity. */
@RunWith(AndroidJUnit4.class)
public final class SmartCommandSelectionInstrumentationTest {
    private static final String TAG = "SmartCommandSelection";

    @Test
    public void creationCommandOwnsSelectionAfterMultiSelect() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = find(activity.getWindow().getDecorView());
                assertNotNull(c);
                c.clearAll();

                c.executeCommand("LINE 40 40 140 40");
                CadCanvasView.Entity first = c.selected;
                assertNotNull(first);
                c.executeCommand("CIRCLE 220 100 30");
                CadCanvasView.Entity second = c.selected;
                assertNotNull(second);

                c.selectedObjects.clear();
                c.selectedObjects.add(first);
                c.selectedObjects.add(second);
                c.selected = null;

                int before = c.entities.size();
                String result = c.executeCommand("LINE 300 180 390 240");
                assertEquals(before + 1, c.entities.size());
                assertNotNull("Creation command reported success but left base selection null: " + result, c.selected);
                assertEquals("Creation command must collapse stale multi-selection", 1, c.selectedObjects.size());
                assertSame("Smart selection and base selection must reference the same new entity",
                        c.selected, c.selectedObjects.get(0));
                assertSame("Newest entity must own selection", c.entities.get(c.entities.size() - 1), c.selected);
                Log.i(TAG, "COMMAND_SELECTION_RESULT selected=true smartCount=1 newest=true");
            });
        }
    }

    private static Shapr3DGuideCadCanvasView find(android.view.View v) {
        if (v instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView c = find(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }
}
