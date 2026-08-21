package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WorkspaceVisualProjectStateInstrumentationTest {

    @Test
    public void materialPresetColorRoughnessAndMetallicRoundTripExactly() {
        CadMaterialPreset.State material = new CadMaterialPreset.State(
                CadMaterialPreset.Preset.METAL, 0xFF27496D, 0.63f, 0.82f);
        SectionViewController section = new SectionViewController();

        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                WorkspaceVisualProjectState.encode(material, section));

        assertEquals(CadMaterialPreset.Preset.METAL, decoded.appearance.preset);
        assertEquals(0xFF27496D, decoded.appearance.argb);
        assertEquals(0.63f, decoded.appearance.roughness, 0.0001f);
        assertEquals(0.82f, decoded.appearance.metallic, 0.0001f);
    }

    @Test
    public void enabledSectionPlaneOffsetAndFlipRoundTripExactly() {
        CadMaterialPreset.State material = CadMaterialPreset.of(CadMaterialPreset.Preset.WOOD);
        SectionViewController section = new SectionViewController();
        section.enable(SectionViewController.Axis.X);
        section.setOffsetMm(-18.75);
        section.flip();

        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                WorkspaceVisualProjectState.encode(material, section));

        assertTrue(decoded.sectionEnabled);
        assertEquals(SectionViewController.Axis.X, decoded.sectionAxis);
        assertEquals(-18.75, decoded.sectionOffsetMm, 0.000001);
        assertTrue(decoded.sectionFlipped);
    }

    @Test
    public void disabledSectionStillPreservesItsLastPlaneCalibration() {
        SectionViewController section = new SectionViewController();
        section.enable(SectionViewController.Axis.Y);
        section.setOffsetMm(31.2);
        section.flip();
        section.disable();

        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                WorkspaceVisualProjectState.encode(CadMaterialPreset.of(CadMaterialPreset.Preset.PAINT), section));

        assertFalse(decoded.sectionEnabled);
        assertEquals(SectionViewController.Axis.Y, decoded.sectionAxis);
        assertEquals(31.2, decoded.sectionOffsetMm, 0.000001);
        assertTrue(decoded.sectionFlipped);
    }

    @Test
    public void allFiveMaterialPresetKeysRemainPortable() {
        for (CadMaterialPreset.Preset preset : CadMaterialPreset.Preset.values()) {
            SectionViewController section = new SectionViewController();
            WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                    WorkspaceVisualProjectState.encode(CadMaterialPreset.of(preset), section));
            assertEquals(preset, decoded.appearance.preset);
        }
    }

    @Test
    public void malformedOrUnknownVisualStateIsRejectedInsteadOfSilentlyReset() {
        assertRejected("{}");
        assertRejected("{\"version\":1,\"appearance\":{\"preset\":\"unknown\",\"argb\":-1,\"roughness\":0.5,\"metallic\":0},\"section\":{\"enabled\":false,\"axis\":\"Z\",\"offsetMm\":0,\"flipped\":false}}");
        assertRejected("{\"version\":1,\"appearance\":{\"preset\":\"wood\",\"argb\":-1,\"roughness\":4.5,\"metallic\":0},\"section\":{\"enabled\":false,\"axis\":\"Z\",\"offsetMm\":0,\"flipped\":false}}");
        assertRejected("{\"version\":1,\"appearance\":{\"preset\":\"wood\",\"argb\":-1,\"roughness\":0.5,\"metallic\":0},\"section\":{\"enabled\":true,\"axis\":\"Q\",\"offsetMm\":0,\"flipped\":false}}");
    }

    private static void assertRejected(String raw) {
        try {
            WorkspaceVisualProjectState.decode(raw);
            fail("Expected invalid visual workspace state to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
