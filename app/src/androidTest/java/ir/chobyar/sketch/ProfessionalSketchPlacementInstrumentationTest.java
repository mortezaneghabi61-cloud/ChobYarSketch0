package ir.chobyar.sketch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Production contracts for plane-aware Sketch placement and construction Items. */
@RunWith(AndroidJUnit4.class)
public final class ProfessionalSketchPlacementInstrumentationTest {
    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        return new K33MirroredCadCanvasView(context);
    }

    private static void onMain(Runnable task) {
        final Throwable[] failure = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { task.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    @Test public void constructOffsetCreatesPlaneWithoutSketch() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String sketchBefore = cad.activeSketchStableId();
            String planeId = cad.createOffsetConstructionPlane(12.5f, "Shelf datum");

            assertTrue(cad.hasConstructionPlane(planeId));
            assertEquals(sketchBefore, cad.activeSketchStableId());
            assertEquals(12.5, cad.constructionPlaneOffsetMm(planeId), 0.0);
            assertTrue(cad.is3DOverview());
        });
    }
}
