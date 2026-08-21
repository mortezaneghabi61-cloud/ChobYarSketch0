package ir.chobyar.sketch;

/**
 * Owns visualization state separately from model/history state.
 * UI code supplies FilamentCadSurface::setAppearance as the Sink.
 */
final class CadAppearanceController {
    interface Sink {
        void setAppearance(int color, float roughness, float metallic);
    }

    private CadMaterialPreset.State state = CadMaterialPreset.of(CadMaterialPreset.Preset.WOOD);

    CadMaterialPreset.State state() {
        return state;
    }

    CadMaterialPreset.State applyPreset(CadMaterialPreset.Preset preset, Sink sink) {
        state = CadMaterialPreset.of(preset);
        publish(sink);
        return state;
    }

    CadMaterialPreset.State setRoughness(float roughness, Sink sink) {
        state = state.withRoughness(roughness);
        publish(sink);
        return state;
    }

    CadMaterialPreset.State setColor(int argb, Sink sink) {
        state = state.withColor(argb);
        publish(sink);
        return state;
    }

    void publish(Sink sink) {
        if (sink != null) sink.setAppearance(state.argb, state.roughness, state.metallic);
    }
}
