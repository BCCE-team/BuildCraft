/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import java.io.IOException;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public final class PluggableHolder {
    private static final IdAllocator ID_ALLOC = new IdAllocator("PlugHolder");
    public static final int ID_REMOVE_PLUG = ID_ALLOC.allocId("REMOVE_PLUG");
    public static final int ID_UPDATE_PLUG = ID_ALLOC.allocId("UPDATE_PLUG");
    public static final int ID_CREATE_PLUG = ID_ALLOC.allocId("CREATE_PLUG");
    public static final int ID_SYNC_STATE = ID_ALLOC.allocId("SYNC_STATE");

    public final TilePipeHolder holder;
    public /*final*/ Direction side;
    public PipePluggable pluggable = PipePluggable.EMPTY;
    private CompoundTag unknownData;

    public PluggableHolder(TilePipeHolder holder, Direction side) {
        this.holder = holder;
        this.side = side;
    }

    /** Replaces the live pluggable and discards any preserved unknown tag intentionally superseded by the player. */
    public void setPluggable(PipePluggable pluggable) {
        this.pluggable = pluggable == null ? PipePluggable.EMPTY : pluggable;
        this.unknownData = null;
    }

    // Saving + Loading

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        if (pluggable != PipePluggable.EMPTY) {
            nbt.putString("id", pluggable.definition.identifier.toString());
            nbt.put("data", pluggable.writeToNbt());
        } else if (unknownData != null && !unknownData.isEmpty()) {
            // Preserve the complete original tag so temporarily missing addon pluggables can be restored later.
            return unknownData.copy();
        }
        return nbt;
    }

    public void readFromNbt(CompoundTag nbt) {
        if (pluggable != PipePluggable.EMPTY) {
            holder.eventBus.unregisterHandler(pluggable);
        }
        pluggable = PipePluggable.EMPTY;
        unknownData = null;

        if (nbt.isEmpty()) {
            return;
        }

        String id = nbt.getString("id");
        try {
            ResourceLocation identifier = new ResourceLocation(id);
            PluggableDefinition def = PipeApi.pluggableRegistry.getDefinition(identifier);
            if (def == null) {
                BCLog.logger.warn("Unknown pluggable id '" + id + "'; preserving its NBT until the addon returns");
                unknownData = nbt.copy();
                return;
            }

            PipePluggable loaded = def.readFromNbt(holder, side, nbt.getCompound("data"));
            if (loaded == null) {
                BCLog.logger.warn("Pluggable '" + id + "' returned null while loading; preserving its NBT");
                unknownData = nbt.copy();
                return;
            }
            pluggable = loaded;
            holder.eventBus.registerHandler(pluggable);
        } catch (RuntimeException | LinkageError error) {
            // A broken or temporarily incompatible addon must not make the entire chunk unloadable.
            BCLog.logger.warn("Failed to load pluggable '" + id + "'; preserving its NBT", error);
            pluggable = PipePluggable.EMPTY;
            unknownData = nbt.copy();
        }
    }

    // Network

    /** Called by {@link TilePipeHolder#replacePluggable(Direction, PipePluggable)} to inform clients about the new
     * pluggable. */
    public void sendNewPluggableData() {
        holder.sendMessage(PipeMessageReceiver.PLUGGABLES[side.ordinal()], this::writeCreationPayload);
    }

    public void writeCreationPayload(FriendlyByteBuf buffer) {
        if (pluggable == PipePluggable.EMPTY) {
            buffer.writeByte(ID_REMOVE_PLUG);
        } else {
            buffer.writeByte(ID_CREATE_PLUG);
            buffer.writeUtf(pluggable.definition.identifier.toString(), 64);
            pluggable.writeCreationPayload(buffer);
        }
    }

    public void readCreationPayload(FriendlyByteBuf buffer) throws InvalidInputDataException {
        int id = buffer.readUnsignedByte();
        if (id == ID_CREATE_PLUG) {
            readCreateInternal(buffer);
        } else if (id == ID_REMOVE_PLUG) {
            holder.eventBus.unregisterHandler(pluggable);
            pluggable = PipePluggable.EMPTY;
            unknownData = null;
        } else {
            throw new InvalidInputDataException("Invalid ID for creation! " + ID_ALLOC.getNameFor(id));
        }
    }

    private void readCreateInternal(FriendlyByteBuf buffer) throws InvalidInputDataException {
        ResourceLocation identifier = new ResourceLocation(buffer.readUtf(64));
        PluggableDefinition def = PipeApi.pluggableRegistry.getDefinition(identifier);
        if (def == null) {
            throw new InvalidInputDataException("Unknown remote pluggable \"" + identifier + "\"");
        }
        if (pluggable != PipePluggable.EMPTY) {
            holder.eventBus.unregisterHandler(pluggable);
        }
        pluggable = def.loadFromBuffer(holder, side, buffer);
        unknownData = null;
        holder.eventBus.registerHandler(pluggable);
    }

    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide netSide) {
        if (pluggable == PipePluggable.EMPTY) {
            // A client is never allowed to remove a server pluggable by sending an empty holder update.
            buffer.writeByte(netSide == BCNetworkSide.CLIENT ? ID_REMOVE_PLUG : ID_UPDATE_PLUG);
            return;
        }

        CompoundTag state = pluggable.writeSyncState(netSide);
        if (state != null) {
            buffer.writeByte(ID_SYNC_STATE);
            buffer.writeNbt(state);
        } else {
            buffer.writeByte(ID_UPDATE_PLUG);
            pluggable.writePayload(buffer, netSide);
        }
    }

    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide netSide, BCPacketContext ctx) throws IOException {
        int id = buffer.readUnsignedByte();
        if (netSide == BCNetworkSide.SERVER) {
            if (pluggable == PipePluggable.EMPTY) return;
            if (id == ID_UPDATE_PLUG) {
                pluggable.readPayload(buffer, netSide, ctx);
            } else if (id == ID_SYNC_STATE) {
                CompoundTag state = buffer.readNbt();
                pluggable.readSyncState(state == null ? new CompoundTag() : state, netSide, ctx);
            }
            return;
        }

        if (id == ID_REMOVE_PLUG) {
            holder.eventBus.unregisterHandler(pluggable);
            pluggable = PipePluggable.EMPTY;
            unknownData = null;
        } else if (id == ID_UPDATE_PLUG) {
            if (pluggable != PipePluggable.EMPTY) pluggable.readPayload(buffer, netSide, ctx);
        } else if (id == ID_SYNC_STATE) {
            if (pluggable != PipePluggable.EMPTY) {
                CompoundTag state = buffer.readNbt();
                pluggable.readSyncState(state == null ? new CompoundTag() : state, netSide, ctx);
            }
        } else if (id == ID_CREATE_PLUG) {
            readCreateInternal(buffer);
        } else {
            throw new InvalidInputDataException("Invalid pluggable payload id " + ID_ALLOC.getNameFor(id));
        }
    }

    // Pluggable overrides

    public void onTick() {
        if (pluggable != PipePluggable.EMPTY) {
            pluggable.onTick();
        }
    }

	public void rotate(Rotation axis) {
		side = axis.rotate(side);
		pluggable.rotate(axis);
	}
}
