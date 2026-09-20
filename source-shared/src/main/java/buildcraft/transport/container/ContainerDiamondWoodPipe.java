/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.container;

import java.io.IOException;

import javax.annotation.Nullable;

import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.lib.gui.ContainerPipe;
import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.transport.BCTransportGuis;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWoodDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWoodDiamond.FilterMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerDiamondWoodPipe extends ContainerPipe {
    @Nullable
    public final PipeBehaviourWoodDiamond behaviour;
    
    public static ContainerDiamondWoodPipe create(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
    	ContainerLevelAccess access = createLevelAccess(playerInventory, buf);
        ContainerDiamondWoodPipe menu = access.evaluate((level, pos) -> {
    		BlockEntity tile = level.getBlockEntity(pos);
    		if(tile instanceof IPipeHolder pipeHolder && pipeHolder.getPipe() != Pipe.EMPTY) {
    			if(pipeHolder.getPipe().getBehaviour() instanceof PipeBehaviourWoodDiamond woodDiamond)
    					return new ContainerDiamondWoodPipe(containerId, playerInventory, new ItemHandlerSimple(9), woodDiamond);
    		}
    		return new ContainerDiamondWoodPipe(containerId, playerInventory);
    	}, null);
        return menu != null ? menu : new ContainerDiamondWoodPipe(containerId, playerInventory);
    }

    private ContainerDiamondWoodPipe(int containerId, Inventory inventory) {
        super(inventory, BCTransportGuis.MENU_PIPE_DIAMOND_WOOD.get(), containerId, null);
        this.behaviour = null;
        addFullPlayerInventory(79);
        closeInvalidClientMenu();
    }

    public ContainerDiamondWoodPipe(int containerId, Inventory inventory, IItemHandlerAdv filterInv, PipeBehaviourWoodDiamond behaviour) {
        super(inventory, BCTransportGuis.MENU_PIPE_DIAMOND_WOOD.get(), containerId, behaviour.pipe.getHolder());
        this.behaviour = behaviour;
        behaviour.pipe.getHolder().onPlayerOpen(inventory.player);

        addFullPlayerInventory(79);

        for (int i = 0; i < 9; i++) {
            addSlot(new SlotPhantom(filterInv, i, 8 + i * 18, 18));
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (behaviour != null) {
            behaviour.pipe.getHolder().onPlayerClose(player);
        }
    }

    public void sendNewFilterMode(FilterMode newFilterMode) {
        if (behaviour != null) {
            this.sendMessage(NET_DATA, (buffer) -> buffer.writeEnum(newFilterMode));
        }
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER && id == NET_DATA && behaviour != null) {
            behaviour.filterMode = buffer.readEnum(FilterMode.class);
            behaviour.pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        }
    }
}
