package ir.chobyar.sketch;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Production workspace contract for the Shapr-style main/Construct menu split. */
@RunWith(AndroidJUnit4.class)
public final class ShaprConstructMenuInstrumentationTest {

    @Test
    public void constructOwnsPlaneAndMainRailOrderMatchesWorkspaceContract() {
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                View search = findVisible(root, "Search");
                assertNotNull("Search tool not found", search);
                assertTrue("Search parent must be a tool rail", search.getParent() instanceof ViewGroup);

                ViewGroup rail = (ViewGroup) search.getParent();
                String[] expected = {"Search", "Sketch", "Add", "Construct", "Transform", "Tools"};
                assertEquals("Unexpected main tool count", expected.length, rail.getChildCount());
                for (int i = 0; i < expected.length; i++) {
                    CharSequence description = rail.getChildAt(i).getContentDescription();
                    assertEquals("Unexpected main tool at index " + i, expected[i],
                            description == null ? null : description.toString());
                }

                View construct = findVisible(root, "Construct");
                assertNotNull("Construct tool not found", construct);
                assertTrue("Construct click rejected", construct.performClick());
                assertNotNull("Construct palette must expose Plane", findVisible(root, "Plane"));

                K33MirroredCadCanvasView canvas = findCanvas(root);
                assertNotNull("Production CAD canvas not found", canvas);
                assertEquals("Construct Plane menu must contain only real plane workflows", 4,
                        canvas.constructionPlaneMenuItems().length);
                assertEquals("＋ Offset Sketch Plane", canvas.constructionPlaneMenuItems()[3]);
                for (String item : canvas.constructionPlaneMenuItems()) {
                    assertFalse("Camera actions belong to the View Cube/right controls: " + item,
                            item.contains("View") || item.contains("3D"));
                    assertFalse("Construction Axis must not be exposed without a real backend: " + item,
                            item.contains("Axis"));
                }

                View close = findVisible(root, "Close");
                assertNotNull("Construct palette Close not found", close);
                assertTrue(close.performClick());

                View add = findVisible(root, "Add");
                assertNotNull("Add tool not found after closing Construct", add);
                assertTrue("Add click rejected", add.performClick());
                assertFalse("Plane must not remain in Add after the Construct split",
                        hasVisible(root, "Plane"));
            });
        }
    }

    private static boolean hasVisible(View root, String description) {
        return findVisible(root, description) != null;
    }

    private static View findVisible(View view, String description) {
        CharSequence current = view.getContentDescription();
        if (view.getVisibility() == View.VISIBLE && current != null && description.contentEquals(current)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVisible(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
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
