package ir.chobyar.sketch;

import java.util.Locale;

/**
 * Renderer-only appearance presets for the CAD workspace.
 *
 * These values never participate in OCCT geometry, feature history, dimensions,
 * topology references or export. They are a compact visual contract that can be
 * consumed by FilamentCadSurface.setAppearance(...).
 */
final class CadMaterialPreset {
    enum Preset {
        WOOD("wood", "چوب", 0xFFB98758, 0.68f, 0.00f),
        FABRIC("fabric", "پارچه", 0xFF7A8290, 0.90f, 0.00f),
        PLASTIC("plastic", "پلاستیک", 0xFFD5D8DD, 0.48f, 0.00f),
        METAL("metal", "فلز", 0xFFAAB3BD, 0.28f, 0.88f),
        PAINT("paint", "رنگ", 0xFF3E78C8, 0.38f, 0.04f);

        final String key;
        final String label;
        final int argb;
        final float roughness;
        final float metallic;

        Preset(String key, String label, int argb, float roughness, float metallic) {
            this.key = key;
            this.label = label;
            this.argb = argb;
            this.roughness = roughness;
            this.metallic = metallic;
        }
    }

    static final class State {
        final Preset preset;
        final int argb;
        final float roughness;
        final float metallic;

        State(Preset preset, int argb, float roughness, float metallic) {
            this.preset = preset;
            this.argb = argb;
            this.roughness = clampRoughness(roughness);
            this.metallic = clamp01(metallic);
        }

        State withRoughness(float value) {
            return new State(preset, argb, value, metallic);
        }

        State withColor(int value) {
            return new State(preset, 0xFF000000 | (value & 0x00FFFFFF), roughness, metallic);
        }

        String summary() {
            return preset.label + " • Roughness " + Math.round(roughness * 100f) + "% • Metallic "
                    + Math.round(metallic * 100f) + "%";
        }
    }

    private CadMaterialPreset() {}

    static State of(Preset preset) {
        Preset p = preset == null ? Preset.WOOD : preset;
        return new State(p, p.argb, p.roughness, p.metallic);
    }

    static Preset fromKey(String raw) {
        if (raw == null) return Preset.WOOD;
        String key = raw.trim().toLowerCase(Locale.US);
        for (Preset p : Preset.values()) if (p.key.equals(key)) return p;
        return Preset.WOOD;
    }

    static float clampRoughness(float value) {
        if (!Float.isFinite(value)) return 0.62f;
        return Math.max(0.04f, Math.min(1f, value));
    }

    static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }
}
