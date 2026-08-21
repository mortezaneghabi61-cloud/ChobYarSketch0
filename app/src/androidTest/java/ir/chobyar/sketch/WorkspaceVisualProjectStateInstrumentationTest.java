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

    @Test
    public void appearanceControllerRestoresExactDecodedStateAndPublishesIt() {
        CadMaterialPreset.State saved = new CadMaterialPreset.State(
                CadMaterialPreset.Preset.FABRIC, 0xFF183E57, 0.57f, 0.14f);
        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                WorkspaceVisualProjectState.encode(saved, new SectionViewController()));

        CadAppearanceController controller = new CadAppearanceController();
        final int[] color = new int[1];
        final float[] values = new float[2];
        CadMaterialPreset.State restored = controller.restore(decoded.appearance,
                (argb, roughness, metallic) -> {
                    color[0] = argb;
                    values[0] = roughness;
                    values[1] = metallic;
                });

        assertEquals(CadMaterialPreset.Preset.FABRIC, restored.preset);
        assertEquals(0xFF183E57, color[0]);
        assertEquals(0.57f, values[0], 0.0001f);
        assertEquals(0.14f, values[1], 0.0001f);
    }

    @Test
    public void sectionControllerRestoresDecodedStateAtomically() {
        SectionViewController saved = new SectionViewController();
        saved.enable(SectionViewController.Axis.X);
        saved.setOffsetMm(-42.625);
        saved.flip();
        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectState.decode(
                WorkspaceVisualProjectState.encode(CadMaterialPreset.of(CadMaterialPreset.Preset.PLASTIC), saved));

        SectionViewController restored = new SectionViewController();
        restored.restore(decoded.sectionEnabled, decoded.sectionAxis,
                decoded.sectionOffsetMm, decoded.sectionFlipped);

        assertTrue(restored.isEnabled());
        assertEquals(SectionViewController.Axis.X, restored.axis());
        assertEquals(-42.625, restored.offsetMm(), 0.000001);
        assertTrue(restored.isFlipped());
    }

    @Test
    public void visualAdapterExportsLiveControllerStateWithoutPresetReset() {
        CadAppearanceController appearance = new CadAppearanceController();
        appearance.restore(new CadMaterialPreset.State(
                CadMaterialPreset.Preset.PAINT, 0xFFAA7733, 0.69f, 0.23f), null);
        SectionViewController section = new SectionViewController();
        section.restore(true, SectionViewController.Axis.Y, 14.375, true);

        WorkspaceVisualProjectState.Decoded decoded = WorkspaceVisualProjectAdapter.validate(
                WorkspaceVisualProjectAdapter.exportState(appearance, section));

        assertEquals(CadMaterialPreset.Preset.PAINT, decoded.appearance.preset);
        assertEquals(0xFFAA7733, decoded.appearance.argb);
        assertEquals(0.69f, decoded.appearance.roughness, 0.0001f);
        assertEquals(0.23f, decoded.appearance.metallic, 0.0001f);
        assertTrue(decoded.sectionEnabled);
        assertEquals(SectionViewController.Axis.Y, decoded.sectionAxis);
        assertEquals(14.375, decoded.sectionOffsetMm, 0.000001);
        assertTrue(decoded.sectionFlipped);
    }

    @Test
    public void visualAdapterRestoresBothControllersFromOneValidatedState() {
        CadAppearanceController sourceAppearance = new CadAppearanceController();
        sourceAppearance.restore(new CadMaterialPreset.State(
                CadMaterialPreset.Preset.METAL, 0xFF62717E, 0.41f, 0.91f), null);
        SectionViewController sourceSection = new SectionViewController();
        sourceSection.restore(false, SectionViewController.Axis.X, -9.5, true);
        String raw = WorkspaceVisualProjectAdapter.exportState(sourceAppearance, sourceSection);

        CadAppearanceController restoredAppearance = new CadAppearanceController();
        SectionViewController restoredSection = new SectionViewController();
        final int[] publishedColor = new int[1];
        WorkspaceVisualProjectAdapter.restore(restoredAppearance, restoredSection, raw,
                (argb, roughness, metallic) -> publishedColor[0] = argb);

        assertEquals(CadMaterialPreset.Preset.METAL, restoredAppearance.state().preset);
        assertEquals(0xFF62717E, publishedColor[0]);
        assertEquals(0.41f, restoredAppearance.state().roughness, 0.0001f);
        assertEquals(0.91f, restoredAppearance.state().metallic, 0.0001f);
        assertFalse(restoredSection.isEnabled());
        assertEquals(SectionViewController.Axis.X, restoredSection.axis());
        assertEquals(-9.5, restoredSection.offsetMm(), 0.000001);
        assertTrue(restoredSection.isFlipped());
    }

    @Test
    public void workspaceEnvelopeCarriesVisualStateWithoutExactModel() {
        CadAppearanceController appearance = new CadAppearanceController();
        appearance.restore(new CadMaterialPreset.State(CadMaterialPreset.Preset.PAINT, 0xFF2468AC, 0.41f, 0.07f), null);
        SectionViewController section = new SectionViewController();
        section.enable(SectionViewController.Axis.Y); section.setOffsetMm(12.75); section.flip();
        String visual = WorkspaceVisualProjectAdapter.exportState(appearance, section);
        CadProjectDocument.Decoded decoded = CadProjectDocument.decode(CadProjectDocument.encodeWorkspace("{}", null, null, visual));
        assertTrue(decoded.hasWorkspaceVisual());
        assertFalse(decoded.hasExactModel());
        WorkspaceVisualProjectState.Decoded restored = WorkspaceVisualProjectAdapter.validate(decoded.workspaceVisualState);
        assertEquals(CadMaterialPreset.Preset.PAINT, restored.appearance.preset);
        assertEquals(0xFF2468AC, restored.appearance.argb);
        assertEquals(0.41f, restored.appearance.roughness, 0.0001f);
        assertEquals(SectionViewController.Axis.Y, restored.sectionAxis);
        assertEquals(12.75, restored.sectionOffsetMm, 0.000001);
        assertTrue(restored.sectionFlipped);
    }

    @Test
    public void earlierWorkspaceV3WithoutVisualStateRemainsReadable() {
        CadProjectDocument.Decoded decoded = CadProjectDocument.decode(CadProjectDocument.encodeWorkspace("{}", null, null));
        assertEquals(CadProjectDocument.SCOPE_WORKSPACE_V3, decoded.scope);
        assertFalse(decoded.hasWorkspaceVisual());
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
