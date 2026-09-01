package ir.chobyar.sketch;

import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/** API35 regression fence for the production Filament renderer lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class FilamentLifecycleInstrumentationTest {

    @Test public void repeatedRendererDestroyIsSafeAndIdempotent() throws Exception {
        try (ActivityScenario<ChobYarActivity> scenario =
                     ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> {
                FilamentCadSurface surface = productionSurface(activity);
                assertNotNull(surface);
                surface.destroyRenderer();
                surface.destroyRenderer();
            });
        }
    }

    private static FilamentCadSurface productionSurface(ChobYarActivity activity) {
        try {
            Field field = ChobYarActivity.class.getDeclaredField("gpuSurface");
            field.setAccessible(true);
            return (FilamentCadSurface) field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Production Filament surface is unavailable", e);
        }
    }
}
