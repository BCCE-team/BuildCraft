/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib;

import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.api.v2.ApiLifecycle;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.crops.CropService;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.api.v2.BuiltInApi2Content;
import buildcraft.lib.crops.CropHandlerPlantable;
import buildcraft.lib.crops.CropHandlerReeds;
import buildcraft.lib.registry.PluggableRegistry;
import java.util.Objects;

public class BCLibRegistries {
    /**
     * Initializes API registries that can be used by other BuildCraft modules while Forge is still constructing mods.
     * <p>
     * BuildCraft is distributed as one jar, but Forge still constructs buildcraftlib, buildcrafttransport,
     * buildcraftsilicon, etc. as separate mods. In large modpacks the construction order can differ, so modules must
     * not assume that the buildcraftlib constructor has already populated static API registries.
     */
    public static synchronized void initApiRegistries() {
        if (PipeApi.pluggableRegistry == null) {
            PipeApi.pluggableRegistry = PluggableRegistry.INSTANCE;
        }
    }

    public static void fmlPreInit() {
        BuildCraftApiRuntime.bootstrap();
        if (BuildCraftApiRuntime.INSTANCE.lifecycle() == ApiLifecycle.TYPE_REGISTRATION) {
            BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.CONTENT_REGISTRATION);
        }
        BuiltInApi2Content.register();
        initApiRegistries();

//        ReloadableRegistryManager dataManager = ReloadableRegistryManager.DATA_PACKS;
//        BuildCraftRegistryManager.managerDataPacks = dataManager;
//        dataManager.registerRegistry(GuideBookRegistry.INSTANCE);

        CropService crops = BuildCraftApi.service(BuildCraftServices.CROPS);
        crops.register(Objects.requireNonNull(net.minecraft.resources.Identifier.tryParse("buildcraft:plantable")), -1000, CropHandlerPlantable.INSTANCE);
        crops.register(Objects.requireNonNull(net.minecraft.resources.Identifier.tryParse("buildcraft:reeds")), 100, CropHandlerReeds.INSTANCE);
    }

    public static void fmlInit() {}

    /** Freeze addon-facing registries only after every mod has completed normal registration. */
    public static synchronized void fmlPostInit() {
        ApiLifecycle phase = BuildCraftApiRuntime.INSTANCE.lifecycle();
        if (phase == ApiLifecycle.TYPE_REGISTRATION) {
            BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.CONTENT_REGISTRATION);
            phase = ApiLifecycle.CONTENT_REGISTRATION;
        }
        if (phase == ApiLifecycle.CONTENT_REGISTRATION) {
            BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.FROZEN);
            phase = ApiLifecycle.FROZEN;
        }
        if (phase == ApiLifecycle.FROZEN) {
            BuildCraftApiRuntime.INSTANCE.advanceLifecycle(ApiLifecycle.RUNNING);
        }
    }
}
