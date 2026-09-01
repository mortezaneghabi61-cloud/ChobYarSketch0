package ir.chobyar.sketch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.assertTrue;

/** Regression fence for production Filament teardown ownership. */
@RunWith(AndroidJUnit4.class)
public final class FilamentLifecycleInstrumentationTest {
    private ChobYarActivity activity;

    @After public void after() throws Exception {
        if (activity != null && !activity.isDestroyed()) finish(activity);
    }

    @Test public void rendererTeardownIsIdempotentAfterRealActivityDestroy() throws Exception {
        activity = launch();
        FilamentCadSurface surface = gpuSurface(activity);

        // Exercise the real production lifecycle first: ChobYarActivity.onDestroy()
        // owns the initial FilamentCadSurface.destroyRenderer() call.
        finish(activity);
        activity = null;

        // A stale/delayed teardown path after Activity destruction must be harmless.
        onMain(() -> {
            surface.destroyRenderer();
            surface.destroyRenderer();
            return true;
        });
    }

    @Test public void staleRendererOperationsAreIgnoredAfterDestroy() throws Exception {
        activity = launch();
        FilamentCadSurface surface = gpuSurface(activity);
        finish(activity);
        activity = null;

        // Simulate callbacks/work that were already queued by the UI/render layer
        // before teardown completed. None may reach already-destroyed Filament state.
        onMain(() -> {
            surface.setAppearance(Color.rgb(80, 130, 190), 0.45f, 0.15f);
            surface.setCameraState(null);
            surface.setMesh(new double[]{
                    0.0, 0.0, 0.0,
                    10.0, 0.0, 0.0,
                    0.0, 10.0, 0.0
            });
            surface.doFrame(System.nanoTime());
            surface.destroyRenderer();
            return true;
        });
    }

    @Test public void rendererCanBeRecreatedAfterPreviousActivityDestroy() throws Exception {
        activity = launch();
        FilamentCadSurface firstSurface = gpuSurface(activity);
        finish(activity);
        activity = null;

        activity = launch();
        FilamentCadSurface secondSurface = gpuSurface(activity);
        assertTrue("Recreated Activity must own a fresh GPU surface", secondSurface != firstSurface);
        finish(activity);
        activity = null;

        // A stale owner from the first Activity must remain terminal even after a
        // later renderer has been created and destroyed successfully.
        onMain(() -> {
            firstSurface.destroyRenderer();
            return true;
        });
    }

    private static ChobYarActivity launch() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, ChobYarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity started = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(started instanceof ChobYarActivity);
        return (ChobYarActivity) started;
    }

    private static FilamentCadSurface gpuSurface(ChobYarActivity value) throws Exception {
        Field field = ChobYarActivity.class.getDeclaredField("gpuSurface");
        field.setAccessible(true);
        Object surface = field.get(value);
        assertTrue(surface instanceof FilamentCadSurface);
        return (FilamentCadSurface) surface;
    }

    private static void finish(ChobYarActivity value) throws Exception {
        onMain(() -> {
            value.finish();
            return true;
        });
        long deadline = System.currentTimeMillis() + 5000L;
        while (!value.isDestroyed() && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(25L);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("Activity did not reach destroyed state", value.isDestroyed());
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
