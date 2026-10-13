package dev.bcce.apifixture;

import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.api.v2.module.ModuleService;
import buildcraft.api.v2.registry.RegistrationContext;
import buildcraft.api.v2.reload.DefinitionReloadService;
import buildcraft.api.v2.reload.ReloadChange;
import buildcraft.api.v2.reload.ReloadObserver;
import buildcraft.api.v2.reload.ScriptableDefinitionType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Standalone addon: intentionally imports no BCCE implementation or loader classes. */
public final class FoundationReloadFixtureAddon {
    public static final ScriptableDefinitionType<String> RULES = new ScriptableDefinitionType<>(
        id("foundation:rules"), String.class, FoundationReloadFixtureAddon::decode);
    public static int before;
    public static int after;

    private FoundationReloadFixtureAddon() { }

    private static String decode(Map<String, Object> input) {
        Object value = input.get("value");
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("value required");
        return text;
    }

    public static void register(DefinitionReloadService definitions, ModuleService modules) {
        RegistrationContext owner = () -> "foundation";
        modules.registerExternal(new ModuleInfo(id("foundation:core"), "1.0.0", true),
            List.of(id("buildcraft:lib")), owner);
        definitions.registerType(RULES, owner);
        definitions.registerDecoder(RULES, id("foundation:uppercase"),
            data -> decode(data).toUpperCase(java.util.Locale.ROOT), owner);
        definitions.registerContributor(RULES, id("foundation:code_source"),
            generation -> List.of(buildcraft.api.v2.reload.ScriptedDefinition.value(
                id("foundation:injected"), Map.of("value", "generation-" + generation.id()),
                new buildcraft.api.v2.reload.DefinitionProvenance("foundation", "code", 0))), owner);
        definitions.observe(RULES, id("foundation:observer"), new ReloadObserver<>() {
            @Override public void beforePublish(ReloadChange<String> change) {
                before++;
                if (change.candidate().get(id("foundation:veto")).isPresent()) {
                    throw new IllegalStateException("veto");
                }
            }
            @Override public void afterPublish(ReloadChange<String> change) {
                after++;
                if (change.candidate().get(id("foundation:after_fail")).isPresent()) {
                    throw new IllegalStateException("after-publication error");
                }
            }
        }, owner);
    }

    public static ResourceLocation id(String name) {
        return Objects.requireNonNull(ResourceLocation.tryParse(name));
    }
}
