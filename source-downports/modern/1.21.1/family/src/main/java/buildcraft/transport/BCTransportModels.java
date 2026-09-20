/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.lib.platform.client.ClientModelBaking;
import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.transport.BCTransportClientRenderers;
import buildcraft.transport.internal.pipe.PipeApiClient;
import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import buildcraft.lib.client.model.ModelHolderStatic;
import buildcraft.lib.client.model.ModelHolderVariable;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.plug.PlugBakerSimple;
import buildcraft.lib.expression.DefaultContexts;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.node.value.NodeVariableObject;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.transport.client.PipeBlockColours;
import buildcraft.transport.client.model.ModelPipe;
import buildcraft.transport.client.model.ModelPipeItem;
import buildcraft.transport.client.model.PipeModelCachePluggable;
import buildcraft.transport.client.model.key.KeyPlugBlocker;
import buildcraft.transport.client.model.key.KeyPlugPowerAdaptor;
import buildcraft.transport.client.render.PipeBehaviourRendererStripes;
import buildcraft.transport.client.render.PipeFlowRendererFE;
import buildcraft.transport.client.render.PipeFlowRendererFluids;
import buildcraft.transport.client.render.PipeFlowRendererItems;
import buildcraft.transport.client.render.PipeFlowRendererPower;
import buildcraft.transport.client.render.RenderPipeHolder;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStripes;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.PipeFlowPower;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

public class BCTransportModels {
   // public static final ResourceLocation BLOCKER_LOCATIOn = ResourceLocation.parse("buildcrafttransport:plugs/blocker");
  //  public static final ResourceLocation POWER_ADAPTER_LOCATION = ResourceLocation.parse("buildcrafttransport:plugs/power_adapter");

    public static final ModelHolderStatic BLOCKER;
    public static final ModelHolderStatic POWER_ADAPTER;
    
    private static final ModelHolderVariable STRIPES;
    private static final NodeVariableObject<Direction> STRIPES_DIRECTION;

    public static final IPluggableStaticBaker<KeyPlugBlocker> BAKER_PLUG_BLOCKER;
    public static final IPluggableStaticBaker<KeyPlugPowerAdaptor> BAKER_PLUG_POWER_ADAPTOR;

    private static boolean initialized;

    static {
        ExpressionCompat.setup();
    	BLOCKER = getStaticModel("plugs/blocker");
    	POWER_ADAPTER = getStaticModel("plugs/power_adapter");

        BAKER_PLUG_BLOCKER = new PlugBakerSimple<>(BLOCKER::getCutoutQuads);
        BAKER_PLUG_POWER_ADAPTOR = new PlugBakerSimple<>(POWER_ADAPTER::getCutoutQuads);

        {
            FunctionContext fnCtx = DefaultContexts.createWithAll();
            STRIPES_DIRECTION = fnCtx.putVariableObject("side", Direction.class);
            STRIPES = getModel("pipes/stripes", fnCtx);
        }
    }

    private static ModelHolderStatic getStaticModel(String str) {
        return new ModelHolderStatic("buildcrafttransport:" + str);
    }

    private static ModelHolderVariable getModel(String str, FunctionContext fnCtx) {
        return new ModelHolderVariable("buildcrafttransport:bcmodels/" + str , fnCtx);
    }


    public static synchronized void init() {
        if (initialized) {
            return;
        }
        if (PipeApiClient.registry == null) {
            throw new IllegalStateException("Pipe client registry has not been initialized");
        }
        initialized = true;

        PipeApiClient.registry.registerBaker(KeyPlugBlocker.class, BAKER_PLUG_BLOCKER);
        PipeApiClient.registry.registerBaker(KeyPlugPowerAdaptor.class, BAKER_PLUG_POWER_ADAPTOR);

        PipeApiClient.registry.registerRenderer(PipeFlowItems.class, PipeFlowRendererItems.INSTANCE);
        PipeApiClient.registry.registerRenderer(PipeFlowFluids.class, PipeFlowRendererFluids.INSTANCE);
        PipeApiClient.registry.registerRenderer(PipeFlowPower.class, PipeFlowRendererPower.INSTANCE);
        PipeApiClient.registry.registerRenderer(PipeFlowForgeEnergy.class, PipeFlowRendererFE.INSTANCE);

        PipeApiClient.registry.registerRenderer(PipeBehaviourStripes.class, PipeBehaviourRendererStripes.INSTANCE);
    }

    public static void onBlockEntityRender(ClientRegistration.Renderers event) {
        BCTransportClientRenderers.register(event);
    }
    
    public static void onBlockColor(ClientRegistration.BlockColours event) {
    	event.register(PipeBlockColours.INSTANCE, BCTransportBlocks.pipeHolder.get());
    }
    
	public static void onModelBakePre(ClientModelBaking.Additional event) {
	//	event.register(BLOCKER_LOCATIOn);
	//	event.register(POWER_ADAPTER_LOCATION);
	}
	
    public static void onModelBake(ClientModelBaking.Models event) {
        // BlockPipeHolder is waterloggable, so the baked block model is keyed by the full blockstate variant.
        // Replacing only the legacy empty variant leaves the real in-world pipe on the JSON fallback model.
        putModel(event, "pipe_holder#waterlogged=false", ModelPipe.INSTANCE);
        putModel(event, "pipe_holder#waterlogged=true", ModelPipe.INSTANCE);
    	putModel(event, "pipe_item#inventory", ModelPipeItem.INSTANCE);
//      putModel(event, "obsidian_item#inventory", ModelPipeItem.INSTANCE);

        // Replace the inventory model for every pipe registered through the public pipe registry,
        // including pipes owned by compatibility modules or third-party mods.
        for (var pipeItem : PipeRegistry.INSTANCE.getPipeItemsMap().values()) {
            if (pipeItem instanceof Item item) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    event.getModels().put(new ModelResourceLocation(id, "inventory"), ModelPipeItem.INSTANCE);
                }
            }
        }
    	
  //  	BakedModel blocker = event.getModels().get(BLOCKER_LOCATIOn);
    //	BakedModel adaptor = event.getModels().get(POWER_ADAPTER_LOCATION);
    	
        // Pipe pluggable items use ordinary generated item models. Using the in-world pluggable baked model here makes
        // inventory icons look like they are rendered with bad block lighting. The in-world bakers above still use the
        // pluggable geometry when the parts are installed on pipes.
    	
    }

    public static void onModelBakeComplete() {
        PipeModelCachePluggable.clear();
        PipeFlowRendererItems.onModelBake();
    }

    private static void putModel(ClientModelBaking.Models event, String str, BakedModel model) {
        int variantSeparator = str.indexOf('#');
        String path = variantSeparator >= 0 ? str.substring(0, variantSeparator) : str;
        String variant = variantSeparator >= 0 ? str.substring(variantSeparator + 1) : "";
        event.getModels().put(
            new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCTransport.MODID, path), variant),
            model
        );
    }
    


    public static MutableQuad[] getStripesDynQuads(Direction side) {
        STRIPES_DIRECTION.value = side;
        return STRIPES.getCutoutQuads();
    }
}
