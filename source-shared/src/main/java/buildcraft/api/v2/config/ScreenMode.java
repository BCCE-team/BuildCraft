package buildcraft.api.v2.config;

/** Client screen selection policy, independent of the last manually selected layout. */
public enum ScreenMode {
    SWITCH, LEGACY_ONLY, MODERN_ONLY;

    public boolean switchable() { return this == SWITCH; }
    public boolean useLegacy(boolean lastWasLegacy) { return this == LEGACY_ONLY || (this == SWITCH && lastWasLegacy); }
}
