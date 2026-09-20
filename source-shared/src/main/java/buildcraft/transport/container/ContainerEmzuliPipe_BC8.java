/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.container;

import java.io.IOException;
import java.util.EnumMap;

import javax.annotation.Nullable;

import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.lib.gui.ContainerPipe;
import buildcraft.lib.gui.Widget_Neptune;
import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.transport.BCTransportGuis;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli.SlotIndex;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCPacketContext;

public class ContainerEmzuliPipe_BC8 extends ContainerPipe {
    @Nullable
    public final PipeBehaviourEmzuli behaviour;
    public final EnumMap<SlotIndex, PaintWidget> paintWidgets = new EnumMap<>(SlotIndex.class);

    public static ContainerEmzuliPipe_BC8 create(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
    	ContainerLevelAccess access = createLevelAccess(playerInventory, buf);
        ContainerEmzuliPipe_BC8 menu = access.evaluate((level, pos) -> {
    		BlockEntity tile = level.getBlockEntity(pos);
    		if(tile instanceof IPipeHolder pipeHolder && pipeHolder.getPipe() != Pipe.EMPTY) {
    			if(pipeHolder.getPipe().getBehaviour() instanceof PipeBehaviourEmzuli emzuli)
    			return new ContainerEmzuliPipe_BC8(containerId, playerInventory, new ItemHandlerSimple(4), emzuli);
    		}
    		return new ContainerEmzuliPipe_BC8(containerId, playerInventory);
    	}, null);
        return menu != null ? menu : new ContainerEmzuliPipe_BC8(containerId, playerInventory);
    }

    private ContainerEmzuliPipe_BC8(int containerId, Inventory inventory) {
        super(inventory, BCTransportGuis.MENU_PIPE_EMZULI.get(), containerId, null);
        this.behaviour = null;
        addFullPlayerInventory(84);
        closeInvalidClientMenu();
    }
    
    public ContainerEmzuliPipe_BC8(int containerId, Inventory inventory, IItemHandlerAdv filterInv, PipeBehaviourEmzuli behaviour) {
        super(inventory, BCTransportGuis.MENU_PIPE_EMZULI.get(), containerId, behaviour.pipe.getHolder());
        this.behaviour = behaviour;
        behaviour.pipe.getHolder().onPlayerOpen(inventory.player);

        addFullPlayerInventory(84);

        addSlot(new SlotPhantom(filterInv, 0, 25, 21));
        addSlot(new SlotPhantom(filterInv, 1, 25, 49));
        addSlot(new SlotPhantom(filterInv, 2, 134, 21));
        addSlot(new SlotPhantom(filterInv, 3, 134, 49));

        for (SlotIndex index : SlotIndex.VALUES) {
            createPaintWidget(index);
        }
    }

    private void createPaintWidget(SlotIndex index) {
        PaintWidget widget = new PaintWidget(this, index);
        addWidget(widget);
        paintWidgets.put(index, widget);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (behaviour != null) {
            behaviour.pipe.getHolder().onPlayerClose(player);
        }
    }

    public static class PaintWidget extends Widget_Neptune<ContainerEmzuliPipe_BC8> {
        public final SlotIndex index;

        public PaintWidget(ContainerEmzuliPipe_BC8 container, SlotIndex index) {
            super(container);
            this.index = index;
        }

        public void setColour(DyeColor colour) {
            sendWidgetData((buffer) -> MessageUtil.writeEnumOrNull(buffer, colour));
        }

        @Override
        public void handleWidgetDataServer(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
            DyeColor colour = MessageUtil.readEnumOrNull(buffer, DyeColor.class);
            if (container.behaviour == null) {
                return;
            }
            if (colour == null) {
                container.behaviour.slotColours.remove(index);
            } else {
                container.behaviour.slotColours.put(index, colour);
            }
            container.behaviour.pipe.getHolder().scheduleNetworkGuiUpdate(PipeMessageReceiver.BEHAVIOUR);
        }
    }
}
