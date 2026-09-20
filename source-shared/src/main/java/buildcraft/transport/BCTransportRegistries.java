/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.automation.StripesHandler;
import buildcraft.transport.api2.StripesApi2Bridge;
import buildcraft.transport.internal.pipe.EnumPipeColourType;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeConnectionAPI;
import buildcraft.transport.internal.pipe.PipeFlowType;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.pipe.flow.PipeFlowStructure;
import buildcraft.transport.stripes.PipeExtensionManager;
import buildcraft.transport.stripes.StripesHandlerDispenser;
import buildcraft.transport.stripes.StripesHandlerEntityInteract;
import buildcraft.transport.stripes.StripesHandlerHoe;
import buildcraft.transport.stripes.StripesHandlerMinecartDestroy;
import buildcraft.transport.stripes.StripesHandlerPipes;
import buildcraft.transport.stripes.StripesHandlerPlaceBlock;
import buildcraft.transport.stripes.StripesHandlerPlant;
import buildcraft.transport.stripes.StripesHandlerShears;
import buildcraft.transport.stripes.StripesHandlerUse;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

public class BCTransportRegistries {

    public static void preInit() {
        PipeApi.pipeRegistry = PipeRegistry.INSTANCE;
        PipeApi.extensionManager = PipeExtensionManager.INSTANCE;
        PipeExtensionManager.registerGameplayEvents();

        PipeApi.flowItems = new PipeFlowType(PipeFlowItems::new, PipeFlowItems::new);
        PipeApi.flowFluids = new PipeFlowType(PipeFlowFluids::new, PipeFlowFluids::new);
        PipeApi.flowPower = new PipeFlowType(PipeFlowPower::new, PipeFlowPower::new);
        PipeApi.flowForgeEnergy = new PipeFlowType(PipeFlowForgeEnergy::new, PipeFlowForgeEnergy::new);
        PipeApi.flowStructure = new PipeFlowType(PipeFlowStructure::new, PipeFlowStructure::new);
        PipeApi.flowStructure.fallbackColourType = EnumPipeColourType.BORDER_OUTER;
    }

    public static void init() {
        PipeConnectionAPI.registerConnection(Blocks.BREWING_STAND,
            (world, pos, face, state) -> face.getAxis().getPlane() == Direction.Plane.HORIZONTAL ? 4 / 16F : 0);

        // Built-in Stripes behaviour is registered through the public API2 extension registry.
        registerStripes("plant", StripesApi2Bridge.item(StripesHandlerPlant.INSTANCE::handle));
        registerStripes("shears", StripesApi2Bridge.item(StripesHandlerShears.INSTANCE::handle));
        registerStripes("pipes", StripesApi2Bridge.item(new StripesHandlerPipes()::handle));
        registerStripes("hoe", StripesApi2Bridge.item(StripesHandlerHoe.INSTANCE::handle));

        // Low-priority fallbacks preserve the original BC8 handler order.
        registerStripes("entity_interact", StripesApi2Bridge.item(StripesHandlerEntityInteract.INSTANCE::handle, -100));
        registerStripes("dispenser", StripesApi2Bridge.item(StripesHandlerDispenser.INSTANCE::handle, -100));
        registerStripes("place_block", StripesApi2Bridge.item(StripesHandlerPlaceBlock.INSTANCE::handle, -100));
        registerStripes("use", StripesApi2Bridge.item(StripesHandlerUse.INSTANCE::handle, -100));

        // Block pass.
        registerStripes("minecart_destroy", StripesApi2Bridge.block(StripesHandlerMinecartDestroy.INSTANCE::handle));

        PipeApi.extensionManager.registerRetractionPipe(BCTransportPipes.voidItem);
    }
    private static void registerStripes(String path, StripesHandler handler) {
        var registry = BuildCraftApi.registry(BuildCraftRegistries.STRIPES_HANDLERS);
        var id = java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcrafttransport:" + path));
        registry.register(id, handler, () -> "buildcrafttransport");
    }

}
