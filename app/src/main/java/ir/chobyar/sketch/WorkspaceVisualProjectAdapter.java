package ir.chobyar.sketch;

/**
 * Save/Open adapter for renderer-only workspace state.
 *
 * The adapter is intentionally independent from CadProjectDocument so the file
 * envelope can evolve without coupling Materials or Section View to OCCT History.
 */
final class WorkspaceVisualProjectAdapter {
    private WorkspaceVisualProjectAdapter() {}

    static String exportState(CadAppearanceController appearance, SectionViewController section) {
        if (appearance == null) throw new IllegalArgumentException("Appearance controller is missing");
        if (section == null) throw new IllegalArgumentException("Section View controller is missing");
        return WorkspaceVisualProjectState.encode(appearance.state(), section);
    }

    static WorkspaceVisualProjectState.Decoded validate(String raw) {
        return WorkspaceVisualProjectState.decode(raw);
    }

    static WorkspaceVisualProjectState.Decoded restore(
            CadAppearanceController appearance,
            SectionViewController section,
            String raw,
            CadAppearanceController.Sink sink) {
        if (appearance == null) throw new IllegalArgumentException("Appearance controller is missing");
        if (section == null) throw new IllegalArgumentException("Section View controller is missing");
        WorkspaceVisualProjectState.Decoded decoded = validate(raw);
        appearance.restore(decoded.appearance, sink);
        section.restore(decoded.sectionEnabled, decoded.sectionAxis,
                decoded.sectionOffsetMm, decoded.sectionFlipped);
        return decoded;
    }
}
