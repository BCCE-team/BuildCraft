/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import java.util.Arrays;

import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeDefinition.IPipeCreator;
import buildcraft.transport.internal.pipe.PipeDefinition.IPipeLoader;
import buildcraft.transport.internal.pipe.PipeDefinition.PipeDefinitionBuilder;
import buildcraft.transport.internal.pipe.PipeFlowType;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.pipe.behaviour.PipeBehaviourClay;
import buildcraft.transport.pipe.behaviour.PipeBehaviourCobble;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDaizuli;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondFluid;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondItem;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli;
import buildcraft.transport.pipe.behaviour.PipeBehaviourGold;
import buildcraft.transport.pipe.behaviour.PipeBehaviourIron;
import buildcraft.transport.pipe.behaviour.PipeBehaviourLapis;
import buildcraft.transport.pipe.behaviour.PipeBehaviourLimiter;
import buildcraft.transport.pipe.behaviour.PipeBehaviourObsidian;
import buildcraft.transport.pipe.behaviour.PipeBehaviourQuartz;
import buildcraft.transport.pipe.behaviour.PipeBehaviourSandstone;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStone;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStripes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStructure;
import buildcraft.transport.pipe.behaviour.PipeBehaviourVoid;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWoodDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWoodPower;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;


public class BCTransportPipes {
    public static PipeDefinition structure;

    public static PipeDefinition woodItem;
    public static PipeDefinition woodFluid;
    public static PipeDefinition woodPower;
    public static PipeDefinition woodFe;

    public static PipeDefinition stoneItem;
    public static PipeDefinition stoneFluid;
    public static PipeDefinition stonePower;
    public static PipeDefinition stoneFe;

    public static PipeDefinition cobbleItem;
    public static PipeDefinition cobbleFluid;
    public static PipeDefinition cobblePower;
    public static PipeDefinition cobbleFe;

    public static PipeDefinition quartzItem;
    public static PipeDefinition quartzFluid;
    public static PipeDefinition quartzPower;
    public static PipeDefinition quartzFe;

    public static PipeDefinition goldItem;
    public static PipeDefinition goldFluid;
    public static PipeDefinition goldPower;
    public static PipeDefinition goldFe;

    public static PipeDefinition sandstoneItem;
    public static PipeDefinition sandstoneFluid;
    public static PipeDefinition sandstonePower;
    public static PipeDefinition sandstoneFe;

    public static PipeDefinition ironItem;
    public static PipeDefinition ironFluid;
    public static PipeDefinition ironPower;
    public static PipeDefinition ironFe;

    public static PipeDefinition diamondItem;
    public static PipeDefinition diamondFluid;
    public static PipeDefinition diamondPower;
    public static PipeDefinition diamondFe;

    public static PipeDefinition diaWoodItem;
    public static PipeDefinition diaWoodFluid;
    public static PipeDefinition diaWoodPower;
    public static PipeDefinition diaWoodFe;

    public static PipeDefinition clayItem;
    public static PipeDefinition clayFluid;

    public static PipeDefinition voidItem;
    public static PipeDefinition voidFluid;

    public static PipeDefinition obsidianItem;
    public static PipeDefinition obsidianFluid;

    public static PipeDefinition lapisItem;
    public static PipeDefinition daizuliItem;
    public static PipeDefinition emzuliItem;
    public static PipeDefinition stripesItem;
    
//    public static int tex_int = 0;

    public static void preInit() {
        DefinitionBuilder builder = new DefinitionBuilder();

        builder.logic(PipeBehaviourStructure::new, PipeBehaviourStructure::new);
        builder.builder.enableBorderColouring();
        structure = builder.idTex("structure").flow(PipeApi.flowStructure).define();
        builder.builder.enableColouring();

        builder.logic(PipeBehaviourWood::new, PipeBehaviourWood::new).texSuffixes("_clear", "_filled");
        woodItem = builder.idTexPrefix("wood_item").flowItem().define();
        woodFluid = builder.idTexPrefix("wood_fluid").flowFluid().define();
        builder.logic(PipeBehaviourWoodPower::new, PipeBehaviourWoodPower::new);
        woodPower = builder.idTexPrefix("wood_power").flowPower().define();
        woodFe = builder.idTexPrefix("wood_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourStone::new, PipeBehaviourStone::new);
        stoneItem = builder.idTex("stone_item").flowItem().define();
        stoneFluid = builder.idTex("stone_fluid").flowFluid().define();
        stonePower = builder.idTex("stone_power").flowPower().define();
        stoneFe = builder.idTex("stone_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourCobble::new, PipeBehaviourCobble::new);
        cobbleItem = builder.idTex("cobblestone_item").flowItem().define();
        cobbleFluid = builder.idTex("cobblestone_fluid").flowFluid().define();
        cobblePower = builder.idTex("cobblestone_power").flowPower().define();
        cobbleFe = builder.idTex("cobblestone_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourQuartz::new, PipeBehaviourQuartz::new);
        quartzItem = builder.idTex("quartz_item").flowItem().define();
        quartzFluid = builder.idTex("quartz_fluid").flowFluid().define();
        quartzPower = builder.idTex("quartz_power").flowPower().define();
        quartzFe = builder.idTex("quartz_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourGold::new, PipeBehaviourGold::new);
        goldItem = builder.idTex("gold_item").flowItem().define();
        goldFluid = builder.idTex("gold_fluid").flowFluid().define();
        goldPower = builder.idTex("gold_power").flowPower().define();
        goldFe = builder.idTex("gold_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourSandstone::new, PipeBehaviourSandstone::new);
        sandstoneItem = builder.idTex("sandstone_item").flowItem().define();
        sandstoneFluid = builder.idTex("sandstone_fluid").flowFluid().define();
        sandstonePower = builder.idTex("sandstone_power").flowPower().define();
        sandstoneFe = builder.idTex("sandstone_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourIron::new, PipeBehaviourIron::new).texSuffixes("_clear", "_filled");
        ironItem = builder.idTexPrefix("iron_item").flowItem().define();
        ironFluid = builder.idTexPrefix("iron_fluid").flowFluid().define();

        String[] diamondTextureSuffixes = new String[8];
        diamondTextureSuffixes[0] = "";
        diamondTextureSuffixes[7] = "_itemstack";
        for (Direction face : Direction.values()) {
            diamondTextureSuffixes[face.ordinal() + 1] = "_" + face.getName();
        }

        builder.logic(PipeBehaviourDiamondItem::new, PipeBehaviourDiamondItem::new).texSuffixes(diamondTextureSuffixes);
        builder.builder.itemTex(7);
        diamondItem = builder.idTexPrefix("diamond_item").flowItem().define();

        builder.logic(PipeBehaviourDiamondFluid::new, PipeBehaviourDiamondFluid::new);
        diamondFluid = builder.idTexPrefix("diamond_fluid").flowFluid().define();
        builder.builder.itemTex(0);

        builder.logic(PipeBehaviourLimiter::new, PipeBehaviourLimiter::new).flowPower();
        builder.texSuffixes("_m0", "_m4", "_m8", "_m16", "_m32", "_m64", "_m128");
        builder.builder.itemTex(6);
        ironPower = builder.idTexPrefix("iron_power").define();
        diamondPower = builder.idTexPrefix("diamond_power").define();
        builder.flowForgeEnergy();
        ironFe = builder.idTexPrefix("iron_fe").define();
        diamondFe = builder.idTexPrefix("diamond_fe").define();
        builder.builder.itemTex(0);

        builder.logic(PipeBehaviourWoodDiamond::new, PipeBehaviourWoodDiamond::new).texSuffixes("_clear", "_filled");
        diaWoodItem = builder.idTexPrefix("diamond_wood_item").flowItem().define();
        diaWoodFluid = builder.idTexPrefix("diamond_wood_fluid").flowFluid().define();
        builder.logic(PipeBehaviourWoodPower::new, PipeBehaviourWoodPower::new);
        diaWoodPower = builder.idTexPrefix("diamond_wood_power").flowPower().define();
        diaWoodFe = builder.idTexPrefix("diamond_wood_fe").flowForgeEnergy().define();

        builder.logic(PipeBehaviourClay::new, PipeBehaviourClay::new);
        clayItem = builder.idTex("clay_item").flowItem().define();
        clayFluid = builder.idTex("clay_fluid").flowFluid().define();

        builder.logic(PipeBehaviourVoid::new, PipeBehaviourVoid::new);
        voidItem = builder.idTex("void_item").flowItem().define();
        voidFluid = builder.idTex("void_fluid").flowFluid().define();

        builder.logic(PipeBehaviourObsidian::new, PipeBehaviourObsidian::new);
        obsidianItem = builder.idTex("obsidian_item").flowItem().define();
        // obsidianFluid = builder.idTex("obsidian_fluid").flowFluid().define();

        DyeColor[] colourArray = DyeColor.values();
        String[] texSuffix = new String[16];
        for (int i = 0; i < 16; i++) {
            texSuffix[i] = "_" + colourArray[i].getName();
        }

        builder.logic(PipeBehaviourLapis::new, PipeBehaviourLapis::new).texSuffixes(texSuffix);
        lapisItem = builder.idTexPrefix("lapis_item").flowItem().define();

        String[] texSuffixPlus = Arrays.copyOf(texSuffix, 17);
        texSuffixPlus[16] = "_filled";

        builder.logic(PipeBehaviourDaizuli::new, PipeBehaviourDaizuli::new).texSuffixes(texSuffixPlus);
        daizuliItem = builder.idTexPrefix("daizuli_item").flowItem().define();

        builder.logic(PipeBehaviourEmzuli::new, PipeBehaviourEmzuli::new).texSuffixes("_clear", "_filled");
        emzuliItem = builder.idTexPrefix("emzuli_item").flowItem().define();

        builder.logic(PipeBehaviourStripes::new, PipeBehaviourStripes::new);
        stripesItem = builder.idTex("stripes_item").flowItem().define();

        registerLegacyFeAliases();
    }

    private static void registerLegacyFeAliases() {
        // Legacy definition IDs are kept as read aliases for save compatibility.
        // They are read aliases only: all newly saved definitions use the canonical *_fe IDs.
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:wood_rf", woodFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:cobblestone_rf", cobbleFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:stone_rf", stoneFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:sandstone_rf", sandstoneFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:quartz_rf", quartzFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:iron_rf", ironFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:gold_rf", goldFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:diamond_rf", diamondFe);
        PipeRegistry.INSTANCE.registerAlias("buildcrafttransport:diamond_wood_rf", diaWoodFe);
    }

    private static class DefinitionBuilder {
        public final PipeDefinitionBuilder builder = new PipeDefinitionBuilder();

        public DefinitionBuilder idTexPrefix(String both) {
            return id(both).texPrefix(both);
        }

        public DefinitionBuilder idTex(String both) {
            return id(both).tex(both);
        }

        public DefinitionBuilder id(String post) {
            builder.identifier = ResourceLocation.fromNamespaceAndPath("buildcrafttransport", post);
            return this;
        }

        public DefinitionBuilder tex(String prefix, String... suffixes) {
            return texPrefix(prefix).texSuffixes(suffixes);
        }

        public DefinitionBuilder texPrefix(String prefix) {
            builder.texturePrefix = "buildcrafttransport:pipes/" + prefix;
            return this;
        }

        public DefinitionBuilder texSuffixes(String... suffixes) {
            if (suffixes.length == 0) {
                builder.textureSuffixes = new String[] { "" };
            } else {
                builder.textureSuffixes = suffixes;
            }
            return this;
        }

        public DefinitionBuilder logic(IPipeCreator creator, IPipeLoader loader) {
            builder.logicConstructor = creator;
            builder.logicLoader = loader;
            return this;
        }

        public DefinitionBuilder flowItem() {
            return flow(PipeApi.flowItems);
        }

        public DefinitionBuilder flowFluid() {
            return flow(PipeApi.flowFluids);
        }

        public DefinitionBuilder flowPower() {
            return flow(PipeApi.flowPower);
        }

        public DefinitionBuilder flowForgeEnergy() {
            return flow(PipeApi.flowForgeEnergy);
        }

        public DefinitionBuilder flow(PipeFlowType flow) {
            builder.flow(flow);
            return this;
        }

        public PipeDefinition define() {
            PipeDefinition def = new PipeDefinition(builder);
            PipeRegistry.INSTANCE.registerPipe(def);
            return def;
        }
    }
}
