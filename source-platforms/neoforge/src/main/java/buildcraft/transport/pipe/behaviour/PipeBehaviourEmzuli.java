/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeEventActionActivate;
import buildcraft.transport.internal.pipe.PipeEventStatement;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.container.ContainerEmzuliPipe_BC8;
import buildcraft.transport.statements.ActionExtractionPreset;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class PipeBehaviourEmzuli extends PipeBehaviourWood implements MenuProvider{

    public enum SlotIndex {
        SQUARE(DyeColor.RED),
        CIRCLE(DyeColor.GREEN),
        TRIANGLE(DyeColor.BLUE),
        CROSS(DyeColor.YELLOW);

        public static final SlotIndex[] VALUES = values();

        public final DyeColor colour;

        SlotIndex(DyeColor colour) {
            this.colour = colour;
        }

        public SlotIndex next() {
            // @formatter:off
            switch (this) {
                case SQUARE: return CIRCLE;
                case CIRCLE: return TRIANGLE;
                case TRIANGLE: return CROSS;
                case CROSS: return SQUARE;
                default: throw new IllegalStateException("Unknown SlotIndex - " + this + " - " + this.ordinal());
            }
            // @formatter:on
        }
    }

    public final EnumMap<SlotIndex, DyeColor> slotColours = new EnumMap<>(SlotIndex.class);
    public final ItemHandlerSimple invFilters = new ItemHandlerSimple(4, null);
    private final EnumSet<SlotIndex> activeSlots;
    private final byte[] activatedTtl = new byte[SlotIndex.VALUES.length];
    private SlotIndex currentSlot = null;

    private final IStackFilter filter = this::filterMatches;

    public PipeBehaviourEmzuli(IPipe pipe) {
        super(pipe);
        activeSlots = EnumSet.noneOf(SlotIndex.class);
    }

    public PipeBehaviourEmzuli(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        invFilters.deserializeNBT(TilePipeHolder.getRegistryAccess(pipe), nbt.getCompound("Filters"));
        activeSlots = NBTUtilBC.readEnumSet(nbt.get("activeSlots"), SlotIndex.class);
        currentSlot = NBTUtilBC.readEnum(nbt.get("currentSlot"), SlotIndex.class);
        for (SlotIndex index : SlotIndex.VALUES) {
            byte c = nbt.getByte("slotColors[" + index.ordinal() + "]");
            if (c > 0 && c <= 16) {
                slotColours.put(index, DyeColor.byId(c - 1));
            }
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("Filters", invFilters.serializeNBT(TilePipeHolder.getRegistryAccess(pipe)));
        nbt.put("activeSlots", NBTUtilBC.writeEnumSet(activeSlots, SlotIndex.class));
        nbt.put("currentSlot", NBTUtilBC.writeEnum(currentSlot));
        for (SlotIndex index : SlotIndex.VALUES) {
            DyeColor c = slotColours.get(index);
            nbt.putByte("slotColors[" + index.ordinal() + "]", (byte) (c == null ? 0 : c.getId() + 1));
        }
        return nbt;
    }
    
    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            for (SlotIndex index : SlotIndex.VALUES) {
                DyeColor colour = MessageUtil.readEnumOrNull(buffer, DyeColor.class);
                if (colour == null) {
                    slotColours.remove(index);
                } else {
                    slotColours.put(index, colour);
                }
            }
            activeSlots.clear();
            activeSlots.addAll(MessageUtil.readEnumSet(buffer, SlotIndex.class));
            currentSlot = MessageUtil.readEnumOrNull(buffer, SlotIndex.class);
        }
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(buffer, side);
        if (side == BCNetworkSide.SERVER) {
            for (SlotIndex index : SlotIndex.VALUES) {
                MessageUtil.writeEnumOrNull(buffer, slotColours.get(index));
            }
            MessageUtil.writeEnumSet(buffer, activeSlots, SlotIndex.class);
            MessageUtil.writeEnumOrNull(buffer, currentSlot);
        }
    }

    @Override
    protected int extractItems(IFlowItems flow, Direction dir, int count, FluidAction simulate) {
        if (currentSlot == null && activeSlots.size() > 0) {
            currentSlot = getNextSlot();
        }
        if (currentSlot == null) return 0;
        int extracted = flow.tryExtractItems(count, dir, slotColours.get(currentSlot), filter, simulate);
        if (extracted > 0 && simulate.execute()) {
            currentSlot = getNextSlot();
            pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        }
        return extracted;
    }

    private boolean filterMatches(@Nonnull ItemStack stack) {
        if (currentSlot == null) return false;
        ItemStack current = invFilters.getStackInSlot(currentSlot.ordinal());
        if (StackUtil.isMatchingItemOrList(current, stack)) {
            return true;
        }
        return false;
    }

    @Override
    public void onTick() {
        super.onTick();
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            return;
        }
        for (SlotIndex index : SlotIndex.VALUES) {
            byte val = activatedTtl[index.ordinal()];
            if (val > 0) {
                val--;
                activatedTtl[index.ordinal()] = val;
            }
            if (val == 0) {
                activeSlots.remove(index);
                if (currentSlot == index) {
                    currentSlot = getNextSlot();
                    pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
                }
            }
        }
    }

    private SlotIndex getNextSlot() {
        SlotIndex current = currentSlot == null ? SlotIndex.CROSS : currentSlot;
        int i = SlotIndex.VALUES.length;
        while (i-- > 0) {
            current = current.next();
            if (activeSlots.contains(current) && !invFilters.getStackInSlot(current.ordinal()).isEmpty()) {
                return current;
            }
        }
        return null;
    }

    public SlotIndex getCurrentSlot() {
        return this.currentSlot;
    }

    public EnumSet<SlotIndex> getActiveSlots() {
        return this.activeSlots;
    }

    @Override
    public boolean onPipeActivate(Player player, BlockHitResult trace, Level level, EnumPipePart part) {
        if (EntityUtil.getWrenchHand(player) != null) {
            return super.onPipeActivate(player, trace, level, part);
        }
        if (!player.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(trace.getBlockPos()));
        }
        return true;
    }

    @Override
    public void addActions(PipeEventStatement.AddActionInternal event) {
        super.addActions(event);
        Collections.addAll(event.actions, BCTransportStatements.ACTION_EXTRACTION_PRESET);
    }

    @Override
    public void onActionActivate(PipeEventActionActivate event) {
        super.onActionActivate(event);
        if (event.action instanceof ActionExtractionPreset) {
            ActionExtractionPreset preset = (ActionExtractionPreset) event.action;
            activeSlots.add(preset.index);
            activatedTtl[preset.index.ordinal()] = 2;
        }
    }
    
	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
		return new ContainerEmzuliPipe_BC8(id, inventory, invFilters, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable(pipe.getDefinition().identifier.toLanguageKey("pipe"));
	}
}
