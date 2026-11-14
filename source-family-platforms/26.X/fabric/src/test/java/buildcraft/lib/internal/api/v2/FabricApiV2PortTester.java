package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftFeatures;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.lib.internal.api.v2.registry.ApiRegistryImpl;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression guards for the Fabric 26.3 API v2 wiring and registration boundary. */
final class FabricApiV2PortTester {
    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    @Test
    void fabricPublishesTheComplete26xApiServices() {
        // Public discovery must use META-INF/services rather than an internal singleton.
        var runtime = BuildCraftApi.runtime();
        assertEquals(BuildCraftApi.VERSION, runtime.version());
        assertTrue(runtime.service(BuildCraftServices.WORLD_ACCESS).isPresent());
        assertTrue(runtime.service(BuildCraftServices.WORLD_EVENTS).isPresent());
        assertTrue(runtime.service(BuildCraftServices.DEFINITION_RELOAD).isPresent());
        assertTrue(runtime.service(BuildCraftServices.CLIENT_PRESENTATIONS).isPresent());
        assertTrue(runtime.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS.id()).isPresent());
        assertTrue(runtime.registry(BuildCraftRegistries.MENU_PRESENTATIONS.id()).isPresent());
        assertTrue(runtime.features().supports(BuildCraftFeatures.WORLD_CONTEXT));
        assertTrue(runtime.features().supports(BuildCraftFeatures.WORLD_EVENTS));
        assertTrue(runtime.features().supports(BuildCraftFeatures.RELOAD, 2));
        // Unit test does not alter the global lifecycle: Fabric owns the freeze event.
    }

    @Test
    void aliasesRejectCyclesBeforeMutatingTheirRegistration() {
        var registry = new ApiRegistryImpl<String>();
        var owner = (buildcraft.api.v2.registry.RegistrationContext) () -> "fixture";
        Identifier canonical = id("fixture", "canonical");
        Identifier previous = id("fixture", "previous");
        Identifier legacy = id("fixture", "legacy");
        registry.registerAlias(previous, legacy, owner);
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerAlias(legacy, previous, owner));
        // A rejected alias must not leave the public registry in an invalid state.
        assertEquals(legacy, registry.canonicalId(previous));
        assertEquals(legacy, registry.canonicalId(legacy));
        assertThrows(IllegalStateException.class, registry::validateFreeze);
        registry.register(legacy, "value", owner);
        registry.validateFreeze();
        assertFalse(registry.frozen());
        registry.freeze();
        assertEquals("value", registry.get(previous));
        assertThrows(IllegalStateException.class,
            () -> registry.register(canonical, "late", owner));
    }

    @Test
    void externalModulesValidateGraphAndFreeze() {
        var modules = new ModuleServiceImpl();
        Identifier a = id("fixture", "pipes");
        Identifier b = id("fixture", "engines");
        var owner = (buildcraft.api.v2.registry.RegistrationContext) () -> "fixture";
        modules.registerExternal(new ModuleInfo(a, "1.0", true), List.of(b), owner);
        assertThrows(IllegalStateException.class, modules::validateFreeze);
        modules.registerExternal(new ModuleInfo(b, "1.0", true), List.of(), owner);
        modules.validateFreeze();
        assertTrue(modules.loaded(a));
        assertTrue(modules.loaded(b));
        modules.freeze();
        assertThrows(IllegalStateException.class, () ->
            modules.registerExternal(new ModuleInfo(id("fixture", "new"), "1.0", true), List.of(), owner));
    }

    @Test
    void externalModuleCyclesAreRejected() {
        var modules = new ModuleServiceImpl();
        Identifier a = id("addon", "a");
        Identifier b = id("addon", "b");
        var owner = (buildcraft.api.v2.registry.RegistrationContext) () -> "addon";
        modules.registerExternal(new ModuleInfo(a, "1", true), List.of(b), owner);
        modules.registerExternal(new ModuleInfo(b, "1", true), List.of(a), owner);
        assertThrows(IllegalStateException.class, modules::validateFreeze);
        assertThrows(IllegalStateException.class, modules::freeze);
    }
}
