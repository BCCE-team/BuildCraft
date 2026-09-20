/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.EnumMap;
import java.util.LinkedHashMap;

import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.lib.item.ItemBC_Neptune;
import buildcraft.lib.item.ItemByEnum;
import buildcraft.lib.item.ItemPluggableSimple;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.item.ItemWire;
import buildcraft.transport.pipe.PipeRegistry;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;

public class BCTransportItems {

    public static Item waterproof;


    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCTransport.MODID);

    public static final LinkedHashMap<PipeDefinition, BCRegistryEntry<ItemPipeHolder>> PIPE_MAP = new LinkedHashMap<>();

    public static final BCRegistryEntry<Item> WATER_PROOF;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_STRUCTURE;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_WOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_WOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_WOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_WOOD;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_STONE;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_COBBLE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_COBBLE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_COBBLE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_COBBLE;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_QUARTZ;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_QUARTZ;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_QUARTZ;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_QUARTZ;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_GOLD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_GOLD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_GOLD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_GOLD;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_SAND_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_SAND_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_SAND_STONE;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_SAND_STONE;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_IRON;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_IRON;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_IRON;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_IRON;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_DIAMOND;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_DIAMOND;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_DIAMOND;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_DIAMOND;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_DIAWOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_DIAWOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_POWER_DIAWOOD;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FE_DIAWOOD;


    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_CLAY;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_CLAY;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_VOID;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_VOID;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_OBSIDIAN;
//    public static final BCRegistryEntry<ItemPipeHolder> PIPE_FLUID_OBSIDIAN;

    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_LAPIS;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_DAIZULI;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_EMZULI;
    public static final BCRegistryEntry<ItemPipeHolder> PIPE_ITEM_STRIPES;

    public static final BCRegistryEntry<ItemPluggableSimple> plugBlocker;
    public static final BCRegistryEntry<ItemPluggableSimple> plugPowerAdaptor;
    public static final EnumMap<DyeColor, ItemWire> wires = ItemByEnum.creatItems(ItemWire::new, new Properties().tab(BCTransport.tabPlugs), DyeColor.values(), DyeColor.class, "wire", ITEMS);;

    static {
        WATER_PROOF = ITEMS.register("waterproof",() -> new ItemBC_Neptune("waterproof", new Properties().tab(BCTransport.tabPipes)));
        PIPE_STRUCTURE = makePipeItem(BCTransportPipes.structure);

        // Register them in order of type -- item, fluid, power
        PIPE_ITEM_WOOD = makePipeItem(BCTransportPipes.woodItem);
        PIPE_ITEM_COBBLE = makePipeItem(BCTransportPipes.cobbleItem);
        PIPE_ITEM_STONE = makePipeItem(BCTransportPipes.stoneItem);
        PIPE_ITEM_QUARTZ = makePipeItem(BCTransportPipes.quartzItem);
        PIPE_ITEM_IRON = makePipeItem(BCTransportPipes.ironItem);
        PIPE_ITEM_GOLD = makePipeItem(BCTransportPipes.goldItem);
        PIPE_ITEM_CLAY = makePipeItem(BCTransportPipes.clayItem);
        PIPE_ITEM_SAND_STONE = makePipeItem(BCTransportPipes.sandstoneItem);
        PIPE_ITEM_VOID = makePipeItem(BCTransportPipes.voidItem);
        PIPE_ITEM_OBSIDIAN = makePipeItem(BCTransportPipes.obsidianItem);
        PIPE_ITEM_DIAMOND = makePipeItem(BCTransportPipes.diamondItem);
        PIPE_ITEM_DIAWOOD = makePipeItem(BCTransportPipes.diaWoodItem);
        PIPE_ITEM_LAPIS = makePipeItem(BCTransportPipes.lapisItem);
        PIPE_ITEM_DAIZULI = makePipeItem(BCTransportPipes.daizuliItem);
        PIPE_ITEM_EMZULI = makePipeItem(BCTransportPipes.emzuliItem);
        PIPE_ITEM_STRIPES = makePipeItem(BCTransportPipes.stripesItem);

        PIPE_FLUID_WOOD = makePipeItem(BCTransportPipes.woodFluid);
        PIPE_FLUID_COBBLE = makePipeItem(BCTransportPipes.cobbleFluid);
        PIPE_FLUID_STONE = makePipeItem(BCTransportPipes.stoneFluid);
        PIPE_FLUID_QUARTZ = makePipeItem(BCTransportPipes.quartzFluid);
        PIPE_FLUID_GOLD = makePipeItem(BCTransportPipes.goldFluid);
        PIPE_FLUID_IRON = makePipeItem(BCTransportPipes.ironFluid);
        PIPE_FLUID_CLAY = makePipeItem(BCTransportPipes.clayFluid);
        PIPE_FLUID_SAND_STONE = makePipeItem(BCTransportPipes.sandstoneFluid);
        PIPE_FLUID_VOID = makePipeItem(BCTransportPipes.voidFluid);
        PIPE_FLUID_DIAMOND = makePipeItem(BCTransportPipes.diamondFluid);
        PIPE_FLUID_DIAWOOD = makePipeItem(BCTransportPipes.diaWoodFluid);
        // pipeFluidObsidian = makePipeItem(BCTransportPipes.obsidianFluid);

        PIPE_POWER_WOOD = makePipeItem(BCTransportPipes.woodPower);
        PIPE_FE_WOOD = makePipeItem(BCTransportPipes.woodFe);
        PIPE_POWER_COBBLE = makePipeItem(BCTransportPipes.cobblePower);
        PIPE_FE_COBBLE = makePipeItem(BCTransportPipes.cobbleFe);
        PIPE_POWER_STONE = makePipeItem(BCTransportPipes.stonePower);
        PIPE_FE_STONE = makePipeItem(BCTransportPipes.stoneFe);
        PIPE_POWER_QUARTZ = makePipeItem(BCTransportPipes.quartzPower);
        PIPE_FE_QUARTZ = makePipeItem(BCTransportPipes.quartzFe);
        PIPE_POWER_IRON = makePipeItem(BCTransportPipes.ironPower);
        PIPE_FE_IRON = makePipeItem(BCTransportPipes.ironFe);
        PIPE_POWER_GOLD = makePipeItem(BCTransportPipes.goldPower);
        PIPE_FE_GOLD = makePipeItem(BCTransportPipes.goldFe);
        PIPE_POWER_SAND_STONE = makePipeItem(BCTransportPipes.sandstonePower);
        PIPE_FE_SAND_STONE = makePipeItem(BCTransportPipes.sandstoneFe);
        PIPE_POWER_DIAMOND = makePipeItem(BCTransportPipes.diamondPower);
        PIPE_FE_DIAMOND = makePipeItem(BCTransportPipes.diamondFe);
        PIPE_POWER_DIAWOOD = makePipeItem(BCTransportPipes.diaWoodPower);
        PIPE_FE_DIAWOOD = makePipeItem(BCTransportPipes.diaWoodFe);

        plugBlocker = ITEMS.register("plug_blocker",() -> new ItemPluggableSimple(BCTransportPlugs.blocker, new Properties().tab(BCTransport.tabPlugs)));
        plugPowerAdaptor = ITEMS.register("plug_power_adaptor", () -> new ItemPluggableSimple(
            BCTransportPlugs.powerAdaptor, ItemPluggableSimple.PIPE_BEHAVIOUR_ACCEPTS_RS_POWER, new Properties().tab(BCTransport.tabPlugs)));


    }




    public static void registry(BCRegistryBinder b) {
        ITEMS.register(b);
    }

    public static BCRegistryEntry<ItemPipeHolder> makePipeItem(PipeDefinition def) {
        var t = ITEMS.register(def.identifier.getPath(), () -> PipeRegistry.INSTANCE.createItemForPipe(def));

        PIPE_MAP.put(def, t);
        return t;

    }

}
