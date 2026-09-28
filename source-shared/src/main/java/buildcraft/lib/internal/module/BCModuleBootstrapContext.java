package buildcraft.lib.internal.module;

import java.util.function.Consumer;

import buildcraft.lib.net.BCMessageRegistrar;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.EntityType;

/** Loader bridge used by the legacy module bootstrap. Gameplay/module ownership stays outside loader entrypoints. */
public interface BCModuleBootstrapContext {
    BCRegistryBinder registries();

    BCMessageRegistrar messages();

    void registerConfig(
        String modId,
        BCConfigSpec spec,
        Consumer<String> onLoad,
        Consumer<String> onReload
    );

    void onCommonSetup(Runnable action);

    void onLoadComplete(Runnable action);

    void registerEntityAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier.Builder attributes);

    /** Hook for the few content families that still need a loader-owned native implementation. */
    void registerPlatformContent(BCModules module);

    /** Finalize the native packet transport after the common packet catalogue has been populated. */
    void networkPostInit();
}
