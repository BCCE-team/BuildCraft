package buildcraft.lib.internal.module;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import buildcraft.lib.net.BCMessageRegistrar;
import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.config.FabricConfigEvents;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.RegistryBinding;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

/** Fabric implementation of the loader-neutral module bootstrap boundary. */
public final class FabricModuleBootstrapContext implements BCModuleBootstrapContext {
    private final List<Runnable> commonSetup = new ArrayList<>();
    private final List<Runnable> loadComplete = new ArrayList<>();
    private final Set<String> configModules = new LinkedHashSet<>();
    private boolean finished;

    @Override
    public BCRegistryBinder registries() {
        return RegistryBinding.direct();
    }

    @Override
    public BCMessageRegistrar messages() {
        return FabricNetworkManager::registerCatalogMessage;
    }

    @Override
    public void registerConfig(
        String modId,
        BCConfigSpec spec,
        Consumer<String> onLoad,
        Consumer<String> onReload
    ) {
        ConfigBinding.bind(spec);
        ConfigBinding.listen(onLoad, onReload);
        configModules.add(modId);
    }

    @Override
    public void onCommonSetup(Runnable action) {
        commonSetup.add(action);
    }

    @Override
    public void onLoadComplete(Runnable action) {
        loadComplete.add(action);
    }

    @Override
    public void registerEntityAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier.Builder attributes) {
        FabricDefaultAttributeRegistry.register(type, attributes);
    }

    @Override
    public void registerPlatformContent(BCModules module) {
        // Native fluid definitions are still loader-owned and are closed in Stage 5.10.
        // Keeping the hook here lets the common module initializer own ordering without growing a Fabric module copy.
    }

    @Override
    public void networkPostInit() {
        // Fabric receivers are installed after the whole catalogue is populated by BuildCraftFabric.
    }

    /** Completes the Fabric equivalent of common-setup/load-complete after every module registered its catalogues. */
    public synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        configModules.forEach(FabricConfigEvents::fireLoad);
        commonSetup.forEach(Runnable::run);
        loadComplete.forEach(Runnable::run);
    }
}
