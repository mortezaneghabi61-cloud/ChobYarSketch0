package ir.chobyar.sketch;

import org.json.JSONObject;

/**
 * Portable renderer/workspace state for project persistence.
 *
 * Appearance and Section View are intentionally outside the OCCT feature graph.
 * Persisting them here restores what the user sees without changing exact model
 * geometry, topology, dimensions, History or CAD export.
 */
final class WorkspaceVisualProjectState {
    static final int VERSION = 1;

    static final class Decoded {
        final CadMaterialPreset.State appearance;
        final boolean sectionEnabled;
        final SectionViewController.Axis sectionAxis;
        final double sectionOffsetMm;
        final boolean sectionFlipped;

        Decoded(CadMaterialPreset.State appearance,
                boolean sectionEnabled,
                SectionViewController.Axis sectionAxis,
                double sectionOffsetMm,
                boolean sectionFlipped) {
            this.appearance = appearance;
            this.sectionEnabled = sectionEnabled;
            this.sectionAxis = sectionAxis;
            this.sectionOffsetMm = sectionOffsetMm;
            this.sectionFlipped = sectionFlipped;
        }
    }

    private WorkspaceVisualProjectState() {}

    static String encode(CadMaterialPreset.State appearance, SectionViewController section) {
        if (appearance == null) throw new IllegalArgumentException("Appearance state is missing");
        if (section == null) throw new IllegalArgumentException("Section View state is missing");
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);

            JSONObject material = new JSONObject();
            material.put("preset", appearance.preset.key);
            material.put("argb", appearance.argb);
            material.put("roughness", appearance.roughness);
            material.put("metallic", appearance.metallic);
            root.put("appearance", material);

            JSONObject cut = new JSONObject();
            cut.put("enabled", section.isEnabled());
            cut.put("axis", section.axis().name());
            cut.put("offsetMm", section.offsetMm());
            cut.put("flipped", section.isFlipped());
            root.put("section", cut);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid visual workspace state", e);
        }
    }

    static Decoded decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Visual workspace state is empty");
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", -1) != VERSION) {
                throw new IllegalArgumentException("Unsupported visual workspace version");
            }

            JSONObject material = root.optJSONObject("appearance");
            JSONObject cut = root.optJSONObject("section");
            if (material == null || cut == null) {
                throw new IllegalArgumentException("Visual workspace sections are missing");
            }

            String presetKey = material.optString("preset", "");
            CadMaterialPreset.Preset preset = null;
            for (CadMaterialPreset.Preset p : CadMaterialPreset.Preset.values()) {
                if (p.key.equals(presetKey)) { preset = p; break; }
            }
            if (preset == null) throw new IllegalArgumentException("Unknown material preset");

            int argb = material.getInt("argb");
            double roughnessRaw = material.getDouble("roughness");
            double metallicRaw = material.getDouble("metallic");
            if (!Double.isFinite(roughnessRaw) || roughnessRaw < 0.04 || roughnessRaw > 1.0) {
                throw new IllegalArgumentException("Material roughness is out of range");
            }
            if (!Double.isFinite(metallicRaw) || metallicRaw < 0.0 || metallicRaw > 1.0) {
                throw new IllegalArgumentException("Material metallic is out of range");
            }
            CadMaterialPreset.State appearance = new CadMaterialPreset.State(
                    preset, argb, (float) roughnessRaw, (float) metallicRaw);

            String axisName = cut.optString("axis", "");
            SectionViewController.Axis axis;
            try { axis = SectionViewController.Axis.valueOf(axisName); }
            catch (Exception e) { throw new IllegalArgumentException("Unknown Section View axis"); }
            double offset = cut.getDouble("offsetMm");
            if (!Double.isFinite(offset)) throw new IllegalArgumentException("Section View offset is invalid");

            return new Decoded(appearance,
                    cut.getBoolean("enabled"),
                    axis,
                    offset,
                    cut.getBoolean("flipped"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed visual workspace state", e);
        }
    }
}
