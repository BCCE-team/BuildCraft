/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.transport;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.transport.container.ContainerDiamondPipe;
import buildcraft.transport.container.ContainerDiamondWoodPipe;
import buildcraft.transport.container.ContainerEmzuliPipe_BC8;
import buildcraft.transport.container.ContainerFilteredBuffer_BC8;
import net.minecraft.world.inventory.MenuType;

public class BCTransportGuis {
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", BCTransport.MODID);
    public static final BCRegistryEntry<MenuType<ContainerDiamondWoodPipe>> MENU_PIPE_DIAMOND_WOOD = MENUS.register("pipe_diawood_menu", () -> BCContainerFactory.create(ContainerDiamondWoodPipe::create));
    public static final BCRegistryEntry<MenuType<ContainerDiamondPipe>> MENU_PIPE_DIAMOND = MENUS.register("pipe_diamond_menu",() -> BCContainerFactory.create(ContainerDiamondPipe::create));
    public static final BCRegistryEntry<MenuType<ContainerFilteredBuffer_BC8>> MENU_FILTERED_BUFFER = MENUS.register("pipe_filtered_buffer", () -> BCContainerFactory.create(ContainerFilteredBuffer_BC8::new));
    public static final BCRegistryEntry<MenuType<ContainerEmzuliPipe_BC8>> MENU_PIPE_EMZULI = MENUS.register("pipe_emzuli_menu", () -> BCContainerFactory.create(ContainerEmzuliPipe_BC8::create));
    static void preInit(BCRegistryBinder modEventBus) {
        MENUS.register(modEventBus);
    }
}
