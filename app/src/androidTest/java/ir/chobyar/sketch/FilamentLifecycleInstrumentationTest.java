package ir.chobyar.sketch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

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
        // Current main has no destroyed-state fence, so this second teardown reaches
        // already-destroyed Filament resources and is expected to fail RED.
        onMain(() -> {
            surface.destroyRenderer();
            surface.destroyRenderer();
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
