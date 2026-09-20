/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.energy.menu.ContainerDynamoMJ;
import buildcraft.energy.menu.ContainerEngineFE;
import buildcraft.energy.menu.ContainerEngineIron_BC8;
import buildcraft.energy.menu.ContainerEngineStone_BC8;
import buildcraft.lib.gui.BCContainerFactory;
import net.minecraft.world.inventory.MenuType;

public class BCEnergyGuis {

    public static final BCRegistryEntry<MenuType<ContainerEngineStone_BC8>> MENU_STONE = BCEnergy.MENUS.register("engine_stone_menu", () -> BCContainerFactory.create(ContainerEngineStone_BC8::new));
    public static final BCRegistryEntry<MenuType<ContainerEngineIron_BC8>> MENU_IRON = BCEnergy.MENUS.register("engine_iron_menu", () -> BCContainerFactory.create(ContainerEngineIron_BC8::new));
    public static final BCRegistryEntry<MenuType<ContainerEngineFE>> MENU_FE = BCEnergy.MENUS.register("engine_fe_menu", () -> BCContainerFactory.create(ContainerEngineFE::new));
    public static final BCRegistryEntry<MenuType<ContainerDynamoMJ>> MENU_DYNAMO_MJ = BCEnergy.MENUS.register("dynamo_mj_menu", () -> BCContainerFactory.create(ContainerDynamoMJ::new));

    static void init() {}

}
