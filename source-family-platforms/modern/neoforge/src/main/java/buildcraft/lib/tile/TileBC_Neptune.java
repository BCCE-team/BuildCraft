//? source if >=1.21.1
/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.lib.tile;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.minecraft.persistence.BCBlockEntity;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.permission.IPlayerOwned;
import buildcraft.lib.cache.CachedChunk;
import buildcraft.lib.cache.IChunkCache;
import buildcraft.lib.cache.ITileCache;
import buildcraft.lib.cache.TileCacheRet;
import buildcraft.lib.cache.TileCacheType;
import buildcraft.lib.delta.DeltaManager;
import buildcraft.lib.delta.DeltaManager.EnumDeltaMessage;
import buildcraft.lib.fluid.TankManager;
import buildcraft.lib.migrate.BCVersion;
import buildcraft.lib.misc.ChunkUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.PermissionUtil;
import buildcraft.lib.misc.PermissionUtil.PermissionBlock;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.IPayloadReceiver;
import buildcraft.lib.net.IPayloadWriter;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.MessageUpdateTile;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.lib.tile.item.ItemHandlerManager;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;

import buildcraft.lib.cap.CapabilityHelper;
import buildcraft.lib.client.render.DetachedRenderer.IDetachedRenderer;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.debug.IAdvDebugTarget;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.lib.compat.NbtCompat;

public abstract class TileBC_Neptune extends BCBlockEntity implements IPayloadReceiver, IAdvDebugTarget, IPlayerOwned, IBCCapabilityProvider {

    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.tile");

    protected static final IdAllocator IDS = new IdAllocator("tile");

    /** Used for sending all data used for rendering the tile on a client. This does not include items, power, stages,
     * etc (Unless some are shown in the level) */
    public static final int NET_RENDER_DATA = IDS.allocId("RENDER_DATA");
    /** Used for sending all data in the GUI. Basically what has been omitted from {@link #NET_RENDER_DATA} that is
     * shown in the GUI. */
    public static final int NET_GUI_DATA = IDS.allocId("GUI_DATA");
    /** Used for sending the data that would normally be sent with {@link Container#detectAndSendChanges()}. Note that
     * if no bytes are written then the update message won't be sent. You should detect if any changes have been made to
     * the gui since the last tick, so you don't resend duplicate information if nothing has changed by the next
     * tick. */
    public static final int NET_GUI_TICK = IDS.allocId("GUI_TICK");

    public static final int NET_REN_DELTA_SINGLE = IDS.allocId("REN_DELTA_SINGLE");
    public static final int NET_REN_DELTA_CLEAR = IDS.allocId("REN_DELTA_CLEAR");
    public static final int NET_GUI_DELTA_SINGLE = IDS.allocId("GUI_DELTA_SINGLE");
    public static final int NET_GUI_DELTA_CLEAR = IDS.allocId("GUI_DELTA_CLEAR");

    /** Used for detailed debugging for inspecting every part of the current tile. For example, tanks use this to
     * display which other tanks makeup the whole structure. */
    public static final int NET_ADV_DEBUG = IDS.allocId("DEBUG_DATA");
    public static final int NET_ADV_DEBUG_DISABLE = IDS.allocId("DEBUG_DISABLE");

    /** Used to tell the client to redraw the block. */
    public static final int NET_REDRAW = IDS.allocId("REDRAW");

    protected final CapabilityHelper caps = new CapabilityHelper();
    protected final ItemHandlerManager itemManager = new ItemHandlerManager(this::onSlotChange);
    public final TankManager tankManager = new TankManager();

    /** Handles all of the players that are currently using this tile (have a GUI open) */
    private final Set<Player> usingPlayers = Sets.newIdentityHashSet();
    private GameProfile owner;

    private final IChunkCache chunkCache = new CachedChunk(this);
    private final ITileCache tileCache = TileCacheType.NEIGHBOUR_CACHE.create(this);

    protected final DeltaManager deltaManager = new DeltaManager((gui, type, writer) -> {
        final int id;
        if (type == EnumDeltaMessage.ADD_SINGLE) {
            id = gui ? NET_GUI_DELTA_SINGLE : NET_REN_DELTA_SINGLE;
        } else if (type == EnumDeltaMessage.SET_VALUE) {
            id = gui ? NET_GUI_DELTA_CLEAR : NET_REN_DELTA_CLEAR;
        } else {
            throw new IllegalArgumentException("Unknown delta message type " + type);
        }
        if (gui) {
            createAndSendGuiMessage(id, writer);
        } else {
            createAndSendMessage(id, writer);
        }
    });

    public TileBC_Neptune(BlockEntityType<?> p_155228_, BlockPos p_155229_, BlockState p_155230_) {
        super(p_155228_, p_155229_, p_155230_);
        caps.addProvider(itemManager);
        caps.addItemStorage(itemManager::getItemStorage, EnumPipePart.VALUES);
    }

    // ##################################################
    //
    // Local blockstate + tile entity getters
    //
    // Some getters use cached state when available; callers should treat returned values as
    // snapshots of the currently loaded world state.
    //
    // ##################################################

    @Nullable
    public final BlockState getCurrentStateForBlock(Block expectedBlock) {
        BlockState state = getBlockState();
        if (state.getBlock() == expectedBlock) {
            return state;
        }
        return null;
    }

    public final BlockState getNeighbourState(Direction offset) {
        // Neighbour state reads are intentionally uncached so world changes are observed immediately.
        return getOffsetState(offset.getUnitVec3i());
    }

    /** @param offset The worldPositionition of the {@link BlockState}, <i>relative</i> to this {@link BlockEntity#getPos()}. */
    public final BlockState getOffsetState(Vec3i offset) {
        return getLocalState(worldPosition.offset(offset));
    }

    /** @param pos The <i>absolute</i> position of the {@link BlockState} . */
    public final BlockState getLocalState(BlockPos pos) {
        if (DEBUG && !level.isLoaded(pos)) {
            BCLog.logger.warn(
                "[lib.tile] Ghost-loading block at " + pos.toShortString() + " (from " + getBlockPos().toShortString() + ")"
            );
        }
        return level.getBlockState(pos);
    }

    public final BlockEntity getNeighbourTile(Direction offset) {
        TileCacheRet cached = tileCache.getTile(offset);
        if (cached != null) {
            return cached.tile;
        }
        if (DEBUG && !level.isLoaded(worldPosition)) {
            BCLog.logger.warn(
                "[lib.tile] Ghost-loading tile at " + (worldPosition).toShortString() + " (from " + getBlockPos().toShortString() + ")"
            );
        }
        return level.getBlockEntity(getBlockPos().offset(offset.getUnitVec3i()));
    }

    /** @param offset The worldPositionition of the {@link BlockEntity} to retrieve, <i>relative</i> to this
     *            {@link BlockEntity#getPos()} . */
    public final BlockEntity getOffsetTile(Vec3i offset) {
        return getLocalTile(worldPosition.offset(offset));
    }

    /** @param worldPosition The <i>absolute</i> worldPositionition of the {@link BlockEntity} . */
    public final BlockEntity getLocalTile(BlockPos pos) {
        TileCacheRet cached = tileCache.getTile(pos);
        if (cached != null) {
            return cached.tile;
        }
        if (DEBUG && !level.isLoaded(pos)) {
            BCLog.logger.warn(
                "[lib.tile] Ghost-loading tile at " + pos.toShortString() + " (from " + getBlockPos().toShortString() + ")"
            );
        }
        return level.getBlockEntity(pos);
    }

    public final LevelChunk getContainingChunk() {
        return chunkCache.getChunk(getBlockPos());
    }

    public final LevelChunk getChunk(BlockPos worldPosition) {
        LevelChunk chunk = chunkCache.getChunk(worldPosition);
        if (chunk == null) {
            return ChunkUtil.getChunk(getLevel(), worldPosition, true);
        }
        return chunk;
    }

    // ##################
    //
    // Misc overridables
    //
    // ##################

    /** @return The {@link IdAllocator} that allocates all ID's for this class, and its parent classes. All subclasses
     *         should override this if they allocate their own ids after calling
     *         {@link IdAllocator#makeChild(String)} */
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    /** Checks to see if this tile can update. The base implementation only checks to see if it has a level. */
    public boolean cannotUpdate() {
        return !hasLevel();
    }



    /** Called whenever the block holding this tile is exploded. Called by
     * {@link Block#onBlockExploded(Level, BlockPos, Explosion)} */
    public void onExplode(Explosion explosion) {
    }

    /** Called whenever the block is removed. Called by {@link #onExplode(Explosion)}, and
     * {@link Block#breakBlock(Level, BlockPos, BlockState)} */
    public void onRemove(boolean dropSelf) {
/*        NonNullList<ItemStack> toDrop = NonNullList.create();
        if(dropSelf)
            toDrop.add(this.getBlockState()
                    .getBlock().getCloneItemStack(getBlockState(), null, level, worldPosition, null));
        addDrops(toDrop, 0);
        Containers.dropContents(level, worldPosition, toDrop);*/
    }


    public void setRemoved() {
        super.setRemoved();
        chunkCache.invalidate();
        tileCache.invalidate();
    }

    public void clearRemoved() {
        super.clearRemoved();
        chunkCache.invalidate();
        tileCache.invalidate();
    }

    public void onLoad() {
        super.onLoad();
        chunkCache.invalidate();
        tileCache.invalidate();
    }

    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        chunkCache.invalidate();
        tileCache.invalidate();
    }

    public void update() {
    }

    /** Called whenever {@link #onRemove()} is called (by default). */
    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        itemManager.addDrops(toDrop);
        tankManager.addDrops(toDrop);
    }

    public void onPlacedBy(@Nullable LivingEntity placer, ItemStack stack) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (placer instanceof Player player) {
            GameProfile profile = player.getGameProfile();
            if (profile != null && GameProfileCompat.id(profile) != null) {
                owner = profile;
                setChanged();
                return;
            }
            BCLog.logger.warn("[lib.tile] Player placer did not have a usable owner profile for " + getClass() + " at " + getBlockPos() + ": " + profile);
        } else {
            BCLog.logger.warn("[lib.tile] Non-player placer for " + getClass() + " at " + getBlockPos() + ": " + placer);
        }
        owner = FakePlayerProvider.NULL_PROFILE;
        setChanged();
    }

    public void onPlayerOpen(Player player) {
        // A client GUI/tooltip read must never claim a block before its server owner has arrived.
        if (level == null || level.isClientSide()) return;
        if (owner == null) {
            GameProfile profile = player.getGameProfile();
            if (profile != null && GameProfileCompat.id(profile) != null) {
                owner = profile;
                setChanged();
            } else {
                BCLog.logger.warn("[lib.tile] Player opening GUI did not have a usable owner profile for " + getClass() + " at " + getBlockPos() + ": " + profile);
                owner = FakePlayerProvider.NULL_PROFILE;
                setChanged();
            }
        }
        sendNetworkUpdate(NET_GUI_DATA, player);
        usingPlayers.add(player);
    }

    public void onPlayerClose(Player player) {
        usingPlayers.remove(player);
    }

    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        return tankManager.onActivated(player, worldPosition, hand);
    }

    //Only called when neighbor tile changed
    public void onNeighbourBlockChanged(BlockState state, BlockPos neighbor) {
        tileCache.invalidate();
    }

    //Called on every neighbor changed
    public void neighbourBlockChanged(BlockState state, BlockPos neighbor, boolean harvest) {

    }    @Override
    @Nullable
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction side) {
        return caps.getCapability(capability, side);
    }


    // Item caps
    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before,
        @Nonnull ItemStack after) {
        if (level.isLoaded(worldPosition)) {
            if (getBlockState().hasAnalogOutputSignal()) {
                setChanged();
            } else {
                markChunkDirty();
            }
        }
    }


    /** Cheaper version of {@link #markDirty()} that doesn't update nearby comparators, so all it will do is ensure that
     * the current chunk is saved after the last tick. */
    public void markChunkDirty() {
        if (buildcraft.lib.compat.transfer.TransferJournal.defer(this::markChunkDirty)) return;
        // BlockEntity#setChanged is the cross-version persistence boundary for marking the containing
        // chunk dirty, including pipe state that must survive a world save.
        setChanged();
    }

    // ##################
    //
    // Permission related
    //
    // ##################

    protected boolean hasOwner() {
        return owner != null;
    }

    /**
     * Returns the recorded owner, or null while it is unknown. Passive GUI/compat reads must not claim ownership.
     */
    @Nullable
    public GameProfile getKnownOwner() {
        return owner;
    }

    public GameProfile getOwner() {
        // Return a permission fallback without persisting it as the actual owner.
        return owner == null ? FakePlayerProvider.NULL_PROFILE : owner;
    }

    /** Assigns the attribution used by BuildCraft automation after a copied machine is placed. */
    public final void setOwnerProfile(@Nullable GameProfile profile) {
        if (level != null && level.isClientSide()) {
            return;
        }
        owner = hasProfileIdentity(profile) ? profile : null;
        if (level != null) {
            setChanged();
        }
    }

    private static boolean hasProfileIdentity(@Nullable GameProfile profile) {
        return profile != null
            && (GameProfileCompat.id(profile) != null
                || GameProfileCompat.name(profile) != null && !GameProfileCompat.name(profile).isBlank());
    }

    public PermissionUtil.PermissionBlock getPermBlock() {
        return new PermissionBlock(this, worldPosition);
    }

    public boolean canEditOther(BlockPos other) {
        return PermissionUtil.hasPermission(
            PermissionUtil.PERM_EDIT, getPermBlock(), PermissionUtil.createFrom(level, other)
        );
    }

    public boolean canPlayerEdit(Player player) {
        return PermissionUtil.hasPermission(PermissionUtil.PERM_EDIT, player, getPermBlock());
    }

    public boolean canInteractWith(Player player) {
        if (level.getBlockEntity(worldPosition) != this) {
            return false;
        }
        if (player.blockPosition().distToCenterSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) > 64.0D) {
            return false;
        }
        // edit rather than view because you can normally change the contents from gui interaction
        return canPlayerEdit(player);
    }

    // ##################
    //
    // Network helpers
    //
    // ##################

    /** Tells MC to redraw this block. Note that this sends the NET_REDRAW message. */
    public final void redrawBlock() {
        if (this.hasLevel()) {
            if (level.isClientSide()) {
                // NET_REDRAW is also the model-data invalidation path for dynamic baked models (engines, pipes, etc.).
                // Flags=0 does not reliably dirty the client render section, which can leave the static half of an
                // engine in its previous orientation while the block-entity renderer already uses the new facing.
                requestModelDataUpdate();
                BlockState state = level.getBlockState(worldPosition);
                level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);

                if (DEBUG) {
                    double x = worldPosition.getX() + 0.5;
                    double y = worldPosition.getY() + 0.5;
                    double z = worldPosition.getZ() + 0.5;
                    level.addParticle(ParticleTypes.HEART, x, y, z, 0, 0, 0);
                }
            } else {
                sendNetworkUpdate(NET_REDRAW);
            }
        }
    }

    /** Sends a network update update of the specified ID. */
    public final void sendNetworkUpdate(int id) {
        if (hasLevel()) {
            MessageUpdateTile message = createNetworkUpdate(id);
            if (level.isClientSide()) {
                MessageManager.sendToServer(message);
            } else {
                MessageUtil.sendToAllWatching(level, worldPosition, message);
            }
        }
    }

    public final void sendNetworkGuiTick(Player player) {
        if (hasLevel() && !level.isClientSide()) {
            MessageUpdateTile message = createNetworkUpdate(NET_GUI_TICK);
            if (message.getPayloadSize() <= Short.BYTES) {
                return;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                MessageManager.sendTo(message, serverPlayer);
            }
        }
    }

    public final void sendNetworkGuiUpdate(int id) {
        if (hasLevel()) {
            for (Player player : usingPlayers) {
                sendNetworkUpdate(id, player);
            }
        }
    }

    public final void sendNetworkUpdate(int id, Player target) {
        if (hasLevel() && target instanceof ServerPlayer) {
            MessageUpdateTile message = createNetworkUpdate(id);
            MessageManager.sendTo(message, (ServerPlayer) target);
        }
    }

    public final MessageUpdateTile createNetworkUpdate(final int id) {
        if (hasLevel()) {
            final BCNetworkSide side = level.isClientSide() ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER;
            return createMessage(id, (buffer) -> writePayload(id, buffer, side));
        } else {
            BCLog.logger.warn("Did not have a level at " + worldPosition + "!");
        }
        return null;
    }

    public final void createAndSendMessage(int id, IPayloadWriter writer) {
        if (hasLevel()) {
            Object message = createMessage(id, writer);
            if (level.isClientSide()) {
                MessageManager.sendToServer(message);
            } else {
                MessageUtil.sendToAllWatching(level, worldPosition, message);
            }
        }
    }

    public final void createAndSendGuiMessage(int id, IPayloadWriter writer) {
        if (hasLevel()) {
            Object message = createMessage(id, writer);
            if (level.isClientSide()) {
                MessageManager.sendToServer(message);
            } else {
                MessageUtil.sendToPlayers(usingPlayers, message);
            }
        }
    }

    public final void createAndSendMessage(int id, ServerPlayer player, IPayloadWriter writer) {
        if (hasLevel()) {
            Object message = createMessage(id, writer);
            MessageManager.sendTo(message, player);
        }
    }

    public final void createAndSendGuiMessage(int id, ServerPlayer player, IPayloadWriter writer) {
        if (usingPlayers.contains(player)) {
            createAndSendMessage(id, player, writer);
        }
    }

    public final MessageUpdateTile createMessage(int id, IPayloadWriter writer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeShort(id);
        writer.write(buffer);
        return new MessageUpdateTile(worldPosition, buffer);
    }



    // Minecraft 1.21.5 receives block-entity update tags through ValueInput. Keeping the compatibility overload here
    // silently stops overriding BlockEntity on 1.21.11, which leaves client-side pipes as Pipe.EMPTY forever.
    public void onDataPacket(Connection net, ValueInput input) {
        handleUpdateTag(input);
    }

    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeShort(NET_RENDER_DATA);
            writePayload(NET_RENDER_DATA, new FriendlyByteBuf(buf),
                level.isClientSide() ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            CompoundTag nbt = super.getUpdateTag(registries);
            // ValueInput has no byte-array primitive accessor. Store the payload as unsigned ints on 1.21.5+
            // so chunk-load and block-update synchronization can recover the exact FriendlyByteBuf bytes.
            int[] updateData = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                updateData[i] = bytes[i] & 0xFF;
            }
            nbt.putIntArray("d", updateData);
            return nbt;
        } finally {
            buf.release();
        }
    }

    public void handleUpdateTag(ValueInput input) {
        int[] updateData = input.getIntArray("d").orElse(null);
        if (updateData == null) {
            return;
        }
        byte[] bytes = new byte[updateData.length];
        for (int i = 0; i < updateData.length; i++) {
            int value = updateData[i];
            if ((value & ~0xFF) != 0) {
                BCLog.logger.warn("[lib.tile] Ignoring invalid update-tag byte {} at index {} for {} at {}",
                    value, i, getClass(), worldPosition);
                return;
            }
            bytes[i] = (byte) value;
        }
        handleUpdatePayload(bytes);
    }

    private void handleUpdatePayload(byte[] bytes) {
        if (bytes.length < 2) {
            // Less than two bytes cannot contain the payload ID. Treat this as absent/malformed render data.
            BCLog.logger.warn("[lib.tile] Received an update tag that didn't have enough data for {} at {}",
                getClass(), worldPosition);
            return;
        }
        ByteBuf buf = Unpooled.copiedBuffer(bytes);

        try {
            int id = buf.readUnsignedShort();
            FriendlyByteBuf buffer = new FriendlyByteBuf(buf);
            readPayload(id, buffer, level.isClientSide() ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER, null);
            // Make sure that we actually read the entire message rather than just discarding it.
            MessageUtil.ensureEmpty(buffer, false, getClass() + ", id = " + getIdAllocator().getNameFor(id));
            spawnReceiveParticles(id);
        } catch (Exception e) {
            // A malformed render-data packet for a single tile must NOT disconnect the player on join.
            // (The custom-channel path rejects malformed payloads at the packet boundary and keeps serverbound failures at DEBUG.)
            BCLog.logger.error("[lib.tile] Failed to read update tag for " + getClass() + " at " + worldPosition, e);
        } finally {
            buf.release();
        }
    }

    private void spawnReceiveParticles(int id) {
        if (DEBUG) {

            if (level != null) {
                double x = worldPosition.getX() + 0.5;
                double y = worldPosition.getY() + 0.5;
                double z = worldPosition.getZ() + 0.5;
                double r = 0.01 + (id & 3) / 4.0;
                double g = 0.01 + ((id / 4) & 3) / 4.0;
                double b = 0.01 + ((id / 16) & 3) / 4.0;
                level.addParticle(DustParticleOptions.REDSTONE, x, y, z, r, g, b);
            }
        }
    }

    public final void receivePayload(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
        int id = buffer.readUnsignedShort();
        if (!getIdAllocator().isAllocated(id)) {
            throw new io.netty.handler.codec.DecoderException("Unknown tile payload id " + id + " for " + getClass().getName());
        }

        BCNetworkSide direction = ctx.side();
        readPayload(id, buffer, direction, ctx);
        NetworkSecurity.requireFullyRead(buffer, getClass().getName() + "#" + getIdAllocator().getNameFor(id));

        if (direction == BCNetworkSide.CLIENT) {
            spawnReceiveParticles(id);
        }
    }

    // ######################
    //
    // Network overridables
    //
    // ######################

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        // write render data with gui data
        if (id == NET_GUI_DATA) {

            writePayload(NET_RENDER_DATA, buffer, side);

            if (side == BCNetworkSide.SERVER) {
                MessageUtil.writeGameProfile(buffer, owner);
            }
        }
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                deltaManager.writeDeltaState(false, buffer);
            } else if (id == NET_GUI_DATA) {
                deltaManager.writeDeltaState(true, buffer);
            }
        }
    }

    /** @param ctx The context. Will be null if this is a generic update payload
     * @throws IOException if something went wrong */
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        // read render data with gui data
        if (id == NET_GUI_DATA) {
            readPayload(NET_RENDER_DATA, buffer, side, ctx);

            if (side == BCNetworkSide.CLIENT) {
                owner = MessageUtil.readGameProfile(buffer);
            }
        }
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) deltaManager.receiveDeltaData(false, EnumDeltaMessage.CURRENT_STATE, buffer);
            else if (id == NET_GUI_DATA) deltaManager.receiveDeltaData(true, EnumDeltaMessage.CURRENT_STATE, buffer);
            else if (id == NET_REN_DELTA_SINGLE) deltaManager.receiveDeltaData(
                false, EnumDeltaMessage.ADD_SINGLE, buffer
            );
            else if (id == NET_GUI_DELTA_SINGLE) deltaManager.receiveDeltaData(
                true, EnumDeltaMessage.ADD_SINGLE, buffer
            );
            else if (id == NET_REN_DELTA_CLEAR) deltaManager.receiveDeltaData(
                false, EnumDeltaMessage.SET_VALUE, buffer
            );
            else if (id == NET_GUI_DELTA_CLEAR) deltaManager.receiveDeltaData(true, EnumDeltaMessage.SET_VALUE, buffer);
            else if (id == NET_REDRAW) redrawBlock();
            else if (id == NET_ADV_DEBUG) {
                BCAdvDebugging.setClientDebugTarget(this);
            }
        }
    }

    // ######################
    //
    // NBT handling
    //
    // ######################

    @Override
    protected void readCommonData(BCValueInput input) {
        deltaManager.readFromNBT(input.readCompound("deltas"));
        owner = input.findCompound("owner").map(TileBC_Neptune::readGameProfile).orElse(null);
        input.findCompound("items").ifPresent(tag -> itemManager.deserializeNBT(input.registries(), tag));
        input.findCompound("tanks").ifPresent(tag -> tankManager.deserializeNBT(input.registries(), tag));
    }

    @Override
    protected void writeCommonData(BCValueOutput output) {
        output.writeInt("data-version", BCVersion.CURRENT.dataVersion);
        output.put("deltas", deltaManager.writeToNBT());
        if (hasProfileIdentity(owner)) {
            output.put("owner", writeGameProfile(owner));
        }
        if (!output.hasRegistries()) {
            BCLog.logger.warn("[lib.tile] Cannot persist {} at {} without registry access", getClass().getName(), worldPosition);
            return;
        }
        CompoundTag items = itemManager.serializeNBT(output.registries());
        if (!items.isEmpty()) output.put("items", items);
        CompoundTag tanks = tankManager.serializeNBT(output.registries());
        if (!tanks.isEmpty()) output.put("tanks", tanks);
    }

    protected void migrateOldNBT(int version, CompoundTag nbt) {
        CompoundTag tankComp = NbtCompat.getCompound(nbt, "tank");
        if (!tankComp.isEmpty()) {
            CompoundTag tanks = new CompoundTag();
            tanks.put("tank", tankComp);
            nbt.put("tanks", tanks);
        }
    }

    @Nullable
    private static GameProfile readGameProfile(CompoundTag nbt) {
        UUID id = NbtCompat.hasUUID(nbt, "Id") ? NbtCompat.getUUID(nbt, "Id") : null;
        String name = NbtCompat.contains(nbt, "Name", Tag.TAG_STRING) ? NbtCompat.getString(nbt, "Name") : null;
        if (id == null && (name == null || name.isBlank())) {
            return null;
        }
        return new GameProfile(id, name);
    }

    private static CompoundTag writeGameProfile(GameProfile profile) {
        CompoundTag nbt = new CompoundTag();
        if (GameProfileCompat.id(profile) != null) {
            NbtCompat.putUUID(nbt, "Id", GameProfileCompat.id(profile));
        }
        if (GameProfileCompat.name(profile) != null) {
            nbt.putString("Name", GameProfileCompat.name(profile));
        }
        return nbt;
    }

    public void requestModelDataUpdate() {
//		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        super.requestModelDataUpdate();
    }



/*    @Override
    protected void setLevelCreate(Level level) {
        // The default impl doesn't actually set the level for some reason :/
        setLevel(level);
    }*/

    // ##################
    //
    // Advanced debugging
    //
    // ##################

    public boolean isBeingDebuggWed() {
        return BCAdvDebugging.isBeingDebugged(this);
    }

    public void enableDebugging() {
        if (level.isClientSide()) {
            return;
        }
        BCAdvDebugging.setCurrentDebugTarget(this);
    }

    public void disableDebugging() {
        sendNetworkUpdate(NET_ADV_DEBUG_DISABLE);
    }

    public boolean doesExistInWorld() {
        return hasLevel() && level.getBlockEntity(worldPosition) == this;
    }

    public void sendDebugState() {
        sendNetworkUpdate(NET_ADV_DEBUG);
    }

    public IDetachedRenderer getDebugRenderer() {
        return null;
    }

    public void rotate(Rotation axis) {
    }
}
