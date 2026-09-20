/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.silicon;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.silicon.container.ContainerAdvancedCraftingTable;
import buildcraft.silicon.container.ContainerChargingTable;
import buildcraft.silicon.container.ContainerAssemblyTable;
import buildcraft.silicon.container.ContainerGate;
import buildcraft.silicon.container.ContainerIntegrationTable;
import buildcraft.silicon.container.ContainerProgrammingTable;
import net.minecraft.world.inventory.MenuType;

public class BCSiliconGuis {
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", BCSilicon.MODID);
    public static final BCRegistryEntry<MenuType<ContainerAdvancedCraftingTable>> MENU_AD_CRAFTING_TABLE = MENUS.register("advanced_crafting_table_menu", () -> BCContainerFactory.create(ContainerAdvancedCraftingTable::new));
    public static final BCRegistryEntry<MenuType<ContainerChargingTable>> MENU_CHARGING_TABLE = MENUS.register("charging_table_menu", () -> BCContainerFactory.create(ContainerChargingTable::new));
    public static final BCRegistryEntry<MenuType<ContainerAssemblyTable>> MENU_ASSEMBLY_TABLE = MENUS.register("assembly_table_menu", () -> BCContainerFactory.create(ContainerAssemblyTable::new));
    public static final BCRegistryEntry<MenuType<ContainerGate>> MENU_GATE = MENUS.register("gate_menu", () -> BCContainerFactory.create(ContainerGate::creatClientMenu));
    public static final BCRegistryEntry<MenuType<ContainerIntegrationTable>> MENU_INTEGRATION_TABLE = MENUS.register("integration_table_menu", () -> BCContainerFactory.create(ContainerIntegrationTable::new));
    public static final BCRegistryEntry<MenuType<ContainerProgrammingTable>> MENU_PROGRAMMING_TABLE = MENUS.register("programming_table_menu", () -> BCContainerFactory.create(ContainerProgrammingTable::new));
    static void preInit(BCRegistryBinder modEventBus) {
        MENUS.register(modEventBus);
    }
}
