/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.list;

import java.io.IOException;

import javax.annotation.Nonnull;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.list.ListMatchType;
import buildcraft.core.BCCore;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.widget.WidgetPhantomSlot;
import buildcraft.lib.list.ListHandler;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class ContainerList extends MenuBC_Neptune {
    // Network ID's

    protected static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("list");
    private static final int ID_LABEL = IDS.allocId("LABEL");
    private static final int ID_BUTTON = IDS.allocId("BUTTON");

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    // Main container list

    public ListHandler.Line[] lines;

    final WidgetListSlot[][] slots;

    class WidgetListSlot extends WidgetPhantomSlot {
        final int lineIndex, slotIndex;

        public WidgetListSlot(int lineIndex, int slotIndex) {
            super(ContainerList.this);
            this.lineIndex = lineIndex;
            this.slotIndex = slotIndex;
        }

        @Override
        protected void onSetStack() {
            ContainerList.this.setStack(lineIndex, slotIndex, getStack());
        }
    }

    private final InteractionHand hand;

    public ContainerList(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, findListHand(playerInventory.player));
    }

    public ContainerList(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory, data != null && data.isReadable()
            ? data.readEnum(InteractionHand.class)
            : findListHand(playerInventory.player));
    }

    public ContainerList(int containerId, Inventory playerInventory, InteractionHand hand) {
        super(playerInventory, BCCore.LIST_MENU.get(), containerId);
        this.hand = hand;

        lines = ListHandler.getLines(getListItemStack(), playerInventory.player.level().registryAccess());

        slots = new WidgetListSlot[lines.length][ListHandler.WIDTH];
        for (int line = 0; line < lines.length; line++) {
            for (int slot = 0; slot < ListHandler.WIDTH; slot++) {
                WidgetListSlot widget = new WidgetListSlot(line, slot);
                slots[line][slot] = addWidget(widget);
                widget.setStack(lines[line].getStack(slot), false);
            }
        }

        addFullPlayerInventory(103);
    }

    @Override
	public boolean stillValid(Player player) {
        return !getListItemStack().isEmpty();
    }

    private static InteractionHand findListHand(Player player) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (BuildCraftApi.service(BuildCraftServices.ITEM_LISTS).isList(mainHand)) {
            return InteractionHand.MAIN_HAND;
        }
        return InteractionHand.OFF_HAND;
    }

    @Nonnull
    public ItemStack getListItemStack() {
        ItemStack stack = playerInventory.player.getItemInHand(hand);
        return BuildCraftApi.service(BuildCraftServices.ITEM_LISTS).isList(stack) ? stack : StackUtil.EMPTY;
    }

    void setStack(final int lineIndex, final int slotIndex, @Nonnull final ItemStack stack) {
        if (lineIndex < 0 || lineIndex >= lines.length || slotIndex < 0 || slotIndex >= ListHandler.WIDTH) {
            return;
        }
        lines[lineIndex].setStack(slotIndex, stack);
        ListHandler.saveLines(getListItemStack(), lines, playerInventory.player.level().registryAccess());
    }

    public void switchButton(final int lineIndex, final int button) {
        if (lineIndex < 0 || lineIndex >= lines.length || button < 0 || button >= 3) {
            return;
        }
        lines[lineIndex].toggleOption(button);

        if (playerInventory.player.level().isClientSide) {
            sendMessage(ID_BUTTON, (buffer) -> {
                buffer.writeByte(lineIndex);
                buffer.writeByte(button);
            });
        } else if (button == 1 || button == 2) {
            ListMatchType type = lines[lineIndex].getSortingType();
            if (type == ListMatchType.MATERIAL || type == ListMatchType.TYPE) {
                WidgetListSlot[] widgetSlots = slots[lineIndex];
                for (int i = 1; i < widgetSlots.length; i++) {
                    widgetSlots[i].setStack(StackUtil.EMPTY, true);
                }
            }
        }

        ListHandler.saveLines(getListItemStack(), lines, playerInventory.player.level().registryAccess());
    }

    public void setLabel(final String text) {
        String label = text.length() > 32 ? text.substring(0, 32) : text;
        BuildCraftApi.service(BuildCraftServices.ITEM_LABELS).setLabel(getListItemStack(), label, OperationMode.EXECUTE);

        if (playerInventory.player.level().isClientSide) {
            sendMessage(ID_LABEL, (buffer) -> buffer.writeUtf(label));
        }
    }

    @Override
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER) {
            if (id == ID_BUTTON) {
                int lineIndex = buffer.readUnsignedByte();
                int button = buffer.readUnsignedByte();
                switchButton(lineIndex, button);
            } else if (id == ID_LABEL) {
                setLabel(buffer.readUtf(32));
            }
        }
    }
}
