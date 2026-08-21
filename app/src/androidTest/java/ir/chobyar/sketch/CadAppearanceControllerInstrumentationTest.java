package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CadAppearanceControllerInstrumentationTest {

    private static final class Sink implements CadAppearanceController.Sink {
        int color;
        float roughness;
        float metallic;
        int calls;

        @Override public void setAppearance(int color, float roughness, float metallic) {
            this.color = color;
            this.roughness = roughness;
            this.metallic = metallic;
            calls++;
        }
    }

    @Test
    public void presetPublishesExactFilamentAppearanceTuple() {
        CadAppearanceController controller = new CadAppearanceController();
        Sink sink = new Sink();
        CadMaterialPreset.State state = controller.applyPreset(CadMaterialPreset.Preset.METAL, sink);
        assertEquals(1, sink.calls);
        assertEquals(state.argb, sink.color);
        assertEquals(state.roughness, sink.roughness, 0.0001f);
        assertEquals(state.metallic, sink.metallic, 0.0001f);
        assertEquals(CadMaterialPreset.Preset.METAL, controller.state().preset);
    }

    @Test
    public void roughnessEditDoesNotChangePresetMetallicOrColor() {
        CadAppearanceController controller = new CadAppearanceController();
        Sink sink = new Sink();
        CadMaterialPreset.State before = controller.applyPreset(CadMaterialPreset.Preset.WOOD, sink);
        CadMaterialPreset.State after = controller.setRoughness(0.31f, sink);
        assertEquals(2, sink.calls);
        assertEquals(before.preset, after.preset);
        assertEquals(before.argb, after.argb);
        assertEquals(before.metallic, after.metallic, 0.0001f);
        assertEquals(0.31f, after.roughness, 0.0001f);
        assertEquals(after.roughness, sink.roughness, 0.0001f);
    }
}
