package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CadMaterialPresetInstrumentationTest {

    @Test
    public void exposesFiveStableVisualizationPresets() {
        CadMaterialPreset.Preset[] presets = CadMaterialPreset.Preset.values();
        assertEquals(5, presets.length);
        assertEquals(CadMaterialPreset.Preset.WOOD, CadMaterialPreset.fromKey("wood"));
        assertEquals(CadMaterialPreset.Preset.FABRIC, CadMaterialPreset.fromKey("FABRIC"));
        assertEquals(CadMaterialPreset.Preset.PLASTIC, CadMaterialPreset.fromKey("plastic"));
        assertEquals(CadMaterialPreset.Preset.METAL, CadMaterialPreset.fromKey("metal"));
        assertEquals(CadMaterialPreset.Preset.PAINT, CadMaterialPreset.fromKey("paint"));
    }

    @Test
    public void presetsKeepPhysicalRendererValuesInRange() {
        for (CadMaterialPreset.Preset preset : CadMaterialPreset.Preset.values()) {
            CadMaterialPreset.State state = CadMaterialPreset.of(preset);
            assertTrue(state.roughness >= 0.04f && state.roughness <= 1f);
            assertTrue(state.metallic >= 0f && state.metallic <= 1f);
            assertEquals(0xFF000000, state.argb & 0xFF000000);
        }
        assertTrue(CadMaterialPreset.of(CadMaterialPreset.Preset.METAL).metallic > 0.7f);
        assertTrue(CadMaterialPreset.of(CadMaterialPreset.Preset.WOOD).metallic < 0.1f);
        assertTrue(CadMaterialPreset.of(CadMaterialPreset.Preset.FABRIC).roughness > 0.8f);
    }

    @Test
    public void roughnessIsEditableWithoutChangingPresetOrMetallicResponse() {
        CadMaterialPreset.State metal = CadMaterialPreset.of(CadMaterialPreset.Preset.METAL);
        CadMaterialPreset.State edited = metal.withRoughness(0.73f);
        assertEquals(CadMaterialPreset.Preset.METAL, edited.preset);
        assertEquals(metal.argb, edited.argb);
        assertEquals(metal.metallic, edited.metallic, 0.0001f);
        assertEquals(0.73f, edited.roughness, 0.0001f);
        assertEquals(0.04f, metal.withRoughness(-5f).roughness, 0.0001f);
        assertEquals(1f, metal.withRoughness(8f).roughness, 0.0001f);
    }

    @Test
    public void colorEditKeepsOpaqueVisualStateOnly() {
        CadMaterialPreset.State wood = CadMaterialPreset.of(CadMaterialPreset.Preset.WOOD);
        CadMaterialPreset.State recolored = wood.withColor(0x00112233);
        assertEquals(0xFF112233, recolored.argb);
        assertEquals(wood.roughness, recolored.roughness, 0.0001f);
        assertEquals(wood.metallic, recolored.metallic, 0.0001f);
        assertEquals(CadMaterialPreset.Preset.WOOD, recolored.preset);
    }
}
