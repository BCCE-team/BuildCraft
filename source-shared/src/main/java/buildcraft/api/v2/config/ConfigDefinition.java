package buildcraft.api.v2.config;

import java.util.List;
import java.util.Objects;

/** Loader-independent configuration schema. Values use canonical textual encodings. */
public record ConfigDefinition(String id, Section section, List<String> category, Scope scope,
        Kind kind, String defaultValue, double minimum, double maximum, List<String> choices,
        String nameKey, String descriptionKey, String tooltipKey, String fallbackName,
        String fallbackDescription, String fallbackTooltip, ApplyPolicy applyPolicy) {
    public enum Section { CLIENT, WORLD, BALANCE, /** Legacy addon category; use the three actual sections instead. */ @Deprecated ADDONS }
    private static final java.util.Set<String> BUILTIN_NAMESPACES = java.util.Set.of(
        "buildcraft", "buildcraftlib", "buildcraftcore", "buildcraftenergy", "buildcrafttransport",
        "buildcraftsilicon", "buildcraftbuilders", "buildcraftfactory", "buildcraftrobotics", "buildcraftcompat");

    public String ownerNamespace() { return id.substring(0, id.indexOf(':')); }
    public boolean isAddon() { return section == Section.ADDONS || !BUILTIN_NAMESPACES.contains(ownerNamespace()); }
    public static boolean isBuiltInNamespace(String namespace) { return BUILTIN_NAMESPACES.contains(namespace); }
    /** Effective tab, including definitions sent by an older 2.2 server. */
    public Section editorSection() { return section == Section.ADDONS ? (scope == Scope.CLIENT ? Section.CLIENT : Section.WORLD) : section; }
    public enum Scope { CLIENT, SERVER }
    public enum Kind { BOOLEAN, INTEGER, DECIMAL, STRING, ENUM }
    /** RESTART is deliberately conservative for values cached during registration. */
    public enum ApplyPolicy { RESTART, WORLD_RESTART, LIVE }

    public ConfigDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(section); Objects.requireNonNull(scope);
        Objects.requireNonNull(kind); Objects.requireNonNull(applyPolicy);
        if (!id.matches("[a-z0-9_.-]+:[a-zA-Z0-9_./-]+") || id.length() > 192)
            throw new IllegalArgumentException("Invalid configuration ID: " + id);
        category = List.copyOf(category); choices = List.copyOf(choices);
        if (category.isEmpty() || category.size() > 12 || category.stream().anyMatch(s -> s.isBlank() || s.length() > 96))
            throw new IllegalArgumentException("Invalid category for " + id);
        if ((section == Section.CLIENT && scope != Scope.CLIENT)
                || ((section == Section.WORLD || section == Section.BALANCE) && scope != Scope.SERVER))
            throw new IllegalArgumentException("Invalid scope for " + section);
        if ((kind == Kind.INTEGER || kind == Kind.DECIMAL) && (!Double.isFinite(minimum)
                || !Double.isFinite(maximum) || minimum > maximum))
            throw new IllegalArgumentException("Invalid bounds for " + id);
        if (kind == Kind.ENUM && (choices.isEmpty() || choices.size() > 128
                || choices.stream().anyMatch(s -> s.isBlank() || s.length() > 96)))
            throw new IllegalArgumentException("Invalid enum choices for " + id);
        nameKey = Objects.requireNonNull(nameKey); descriptionKey = Objects.requireNonNull(descriptionKey);
        tooltipKey = Objects.requireNonNull(tooltipKey); fallbackName = Objects.requireNonNull(fallbackName);
        fallbackDescription = Objects.requireNonNull(fallbackDescription); fallbackTooltip = Objects.requireNonNull(fallbackTooltip);
        if (nameKey.length()>512 || descriptionKey.length()>512 || tooltipKey.length()>512 || fallbackName.length()>512 || fallbackDescription.length()>2048 || fallbackTooltip.length()>4096)
            throw new IllegalArgumentException("Configuration metadata too long: " + id);
        defaultValue = normalize(kind, minimum, maximum, choices, defaultValue);
    }

    public String validate(String value) { return normalize(kind, minimum, maximum, choices, value); }

    private static String normalize(Kind kind, double min, double max, List<String> choices, String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > 4096) throw new IllegalArgumentException("Configuration value is too long");
        switch (kind) {
            case BOOLEAN:
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException("Expected true or false");
                return Boolean.toString(Boolean.parseBoolean(value));
            case INTEGER:
                long number = Long.parseLong(value.trim());
                if (number < min || number > max) throw new IllegalArgumentException("Outside range " + min + ".." + max);
                return Long.toString(number);
            case DECIMAL:
                double decimal = Double.parseDouble(value.trim());
                if (!Double.isFinite(decimal) || decimal < min || decimal > max)
                    throw new IllegalArgumentException("Outside range " + min + ".." + max);
                return Double.toString(decimal);
            case ENUM:
                if (!choices.contains(value)) throw new IllegalArgumentException("Unknown choice: " + value);
                return value;
            default: return value;
        }
    }
}
