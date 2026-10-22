/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders;

import buildcraft.builders.registry.FillerRegistry;
import buildcraft.builders.internal.filler.legacy.FillerManager;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.builders.snapshot.TemplateHandlerDefault;
import buildcraft.core.marker.volume.AddonsRegistry;
import net.minecraft.resources.ResourceLocation;

public class BCBuildersRegistries {
    public static void preInit() {
        FillerManager.registry = FillerRegistry.INSTANCE;

        AddonsRegistry.INSTANCE.register(ResourceLocation.parse("buildcraftbuilders:filler_planner"),
            AddonFillerPlanner.class);
    }

    public static void init() {
        BuildCraftApi.service(BuildCraftServices.TEMPLATES).register(
            ResourceLocation.parse("buildcraftbuilders:default"), 0, TemplateHandlerDefault.INSTANCE
        );
    }
}
