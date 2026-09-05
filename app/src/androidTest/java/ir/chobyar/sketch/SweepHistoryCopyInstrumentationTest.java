package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/** Exact user-visible History copy contract for Sweep. */
@RunWith(AndroidJUnit4.class)
public final class SweepHistoryCopyInstrumentationTest {

    @Test public void sweepDetailNamesProfileAndPath() throws Exception {
        Class<?> type=Class.forName("ir.chobyar.sketch.AdvancedParametricSolidCadCanvasView$SweepFeature");
        Constructor<?> ctor=type.getDeclaredConstructor(int.class,Object.class,Object.class);
        ctor.setAccessible(true);
        Object feature=ctor.newInstance(1,new Object(),new Object());

        Method detail=type.getDeclaredMethod("detail");
        detail.setAccessible(true);
        assertEquals("Sweep • Profile + Path",detail.invoke(feature));
    }
}
