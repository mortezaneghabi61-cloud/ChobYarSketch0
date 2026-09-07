package ir.chobyar.sketch;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Production contract for explicit, fail-closed contextual CAD capabilities. */
@RunWith(AndroidJUnit4.class)
public final class ContextualSelectionCapabilitiesInstrumentationTest {

    @Test public void threeDimensionalKindsNeverFallBackToSketchOrGenericDelete() {
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> {
                assertCapabilities(activity, "EDGE", "Deselect All", "Fillet", "Chamfer", "Measure");
                assertCapabilities(activity, "FACE", "Deselect All", "Sketch", "Move/Rotate", "Push/Pull", "Measure");
                assertCapabilities(activity, "BODY", "Deselect All", "Move/Rotate", "Exact Dimensions",
                        "Material", "Section", "Boolean", "Hide");
                assertCapabilities(activity, "VERTEX", "Deselect All");
                assertCapabilities(activity, "NONE");

                for (String kind : new String[]{"EDGE", "FACE", "BODY", "VERTEX", "NONE", "UNKNOWN"}) {
                    List<String> labels = activity.contextualCapabilityLabelsForSelection(kind);
                    assertFalse(kind + " exposed generic Delete", labels.contains("Delete"));
                    assertFalse(kind + " exposed sketch Offset", labels.contains("Offset"));
                    assertFalse(kind + " exposed sketch Trim", labels.contains("Trim"));
                    assertFalse(kind + " exposed sketch Extend", labels.contains("Extend"));
                    assertFalse(kind + " exposed sketch Dimension", labels.contains("Dimension"));
                    assertFalse(kind + " exposed sketch Extrude", labels.contains("Extrude"));
                }
            });
        }
    }

    @Test public void sketchCapabilitiesRemainSeparateAndUseful() {
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> assertCapabilities(activity, "SKETCH", "Deselect All", "Move/Rotate",
                    "Offset", "Trim", "Extend", "Dimension", "Extrude", "Delete"));
        }
    }

    @Test public void selectedBodyVisibilityChangesRealItemsGpuAndProjectState() {
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> {
                K33MirroredCadCanvasView cad = findCanvas(activity.getWindow().getDecorView());
                assertNotNull(cad);
                cad.clearAll();
                assertTrue(cad.executeCommand("RECT 0 0 80 50").contains("Rectangle"));
                assertTrue(cad.executeCommand("EXTRUDE 30").contains("created"));
                assertTrue(cad.isSelectedBodyVisible());
                assertTrue(cad.gpuMesh().length >= 9);

                String result = cad.toggleSelectedBodyVisibility();
                assertTrue(result, result.contains("hidden"));
                assertFalse(cad.isItemVisible(0));
                assertTrue(cad.itemRows()[0].startsWith("○ "));
                assertTrue("Hidden body leaked into GPU mesh", cad.gpuMesh().length == 0);

                String encoded = CadProjectPersistenceController.encode(cad);
                K33MirroredCadCanvasView restored = new K33MirroredCadCanvasView(activity);
                CadProjectPersistenceController.restore(restored, encoded);
                assertFalse("Visibility was not project-owned", restored.isItemVisible(0));
                assertTrue("Restored hidden body leaked into GPU mesh", restored.gpuMesh().length == 0);
            });
        }
    }

    private static void assertCapabilities(ChobYarActivity activity, String kind, String... expected) {
        List<String> actual = activity.contextualCapabilityLabelsForSelection(kind);
        assertTrue(kind + " expected=" + Arrays.toString(expected) + " actual=" + actual,
                actual.equals(Arrays.asList(expected)));
    }

    private static K33MirroredCadCanvasView findCanvas(View view) {
        if (view instanceof K33MirroredCadCanvasView) return (K33MirroredCadCanvasView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                K33MirroredCadCanvasView found = findCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
