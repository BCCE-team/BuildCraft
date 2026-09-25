/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.tile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.tuple.Pair;
import com.google.common.primitives.Bytes;

import io.netty.handler.codec.DecoderException;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.menu.ContainerElectronicLibrary;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.nbt.NbtSquisher;
import buildcraft.lib.net.BCNetwork;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.tile.item.StackInsertionFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileElectronicLibrary extends TileBC_Neptune implements MenuProvider{

	public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("library");
    public static final int NET_DOWN = IDS.allocId("DOWN");
    public static final int NET_UP = IDS.allocId("UP");

    public final ItemHandlerSimple invDownIn = itemManager.addInvHandler(
        "downIn",
        1,
        (slot, stack) -> isUsedSnapshot(stack),
        StackInsertionFunction.getInsertionFunction(1),
        EnumAccess.INSERT,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invDownOut = itemManager.addInvHandler(
        "downOut",
        1,
        StackInsertionFunction.getInsertionFunction(1),
        EnumAccess.EXTRACT,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invUpIn = itemManager.addInvHandler(
        "upIn",
        1,
        (slot, stack) -> isCleanSnapshot(stack),
        StackInsertionFunction.getInsertionFunction(1),
        EnumAccess.INSERT,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invUpOut = itemManager.addInvHandler(
        "upOut",
        1,
        StackInsertionFunction.getInsertionFunction(1),
        EnumAccess.EXTRACT,
        EnumPipePart.VALUES
    );
    public static final int TRANSFER_TIME = 50;

    private static final int MAX_UPLOAD_PART_BYTES = 4 * 1024;
    private static final int MAX_UPLOAD_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int MAX_UPLOAD_PARTS = MAX_UPLOAD_TOTAL_BYTES / MAX_UPLOAD_PART_BYTES + 1;
    private static final int MAX_CONCURRENT_UPLOADS = 64;
    private static final long MAX_UPLOAD_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UPLOAD_NBT_DECODE_BUDGET = 20_000_000L;

    public Snapshot.Key selected = null;
    private int progressDown = -1;
    private int progressUp = -1;
    private int progressDownLast = -1;
    private int progressUpLast = -1;
    private final Map<Pair<UUID, Snapshot.Key>, UploadAccumulator> upSnapshotsParts = new HashMap<>();

    public TileElectronicLibrary(BlockPos pos, BlockState state) {
		super(BCBuildersBlocks.LIBRARY_TILE_BC8.get(), pos, state);
	}

    public static boolean isUsedSnapshot(ItemStack stack) {
        return ItemSnapshot.getHeader(stack) != null;
    }

    public static boolean isCleanSnapshot(ItemStack stack) {
        return stack.getItem() instanceof ItemSnapshot && !ItemSnapshot.EnumItemSnapshotType.getFromStack(stack).used;
    }

    private Snapshot getSnapshotForDownload() {
        Snapshot.Header header = ItemSnapshot.getHeader(invDownIn.getStackInSlot(0));
        return header == null || level == null ? null
            : GlobalSavedDataSnapshots.getSnapshotForConstruction(level, header.key);
    }

    private Snapshot.Header getSelectedHeader() {
        if (selected == null || selected.header == null || !sameContentKey(selected, selected.header.key)) {
            return null;
        }
        return selected.header;
    }

    private static boolean sameContentKey(Snapshot.Key left, Snapshot.Key right) {
        return left != null && right != null && Arrays.equals(left.hash, right.hash);
    }

    private void finishUpload(Snapshot snapshot, Snapshot.Header header) {
        invUpOut.setStackInSlot(0, ItemSnapshot.getUsed(snapshot.getType(), header));
        invUpIn.setStackInSlot(0, StackUtil.EMPTY);
    }
    
    @Override
    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before, @Nonnull ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        if (handler == invDownIn && progressDown > 0) {
            progressDown = -1;
        }
        if (handler == invUpIn && progressUp > 0) {
            progressUp = -1;
        }
    }

    @Override
    public void update() {
        if (level.isClientSide) {
            progressDownLast = progressDown;
            progressUpLast = progressUp;
            return;
        }

        progressDownLast = progressDown;
        progressUpLast = progressUp;

        boolean canDownload = getSnapshotForDownload() != null && invDownOut.getStackInSlot(0).isEmpty();
        if (canDownload) {
            if (progressDown == -1) {
                progressDown = 0;
            }
            if (progressDown >= TRANSFER_TIME) {
                sendNetworkGuiUpdate(NET_DOWN);
                ItemStack downloaded = invDownIn.getStackInSlot(0).copy();
                downloaded.setCount(1);
                invDownOut.setStackInSlot(0, downloaded);
                invDownIn.setStackInSlot(0, StackUtil.EMPTY);
                progressDown = -1;
            } else {
                progressDown++;
            }
        } else if (progressDown != -1) {
            progressDown = -1;
        }

        Snapshot.Header selectedHeader = getSelectedHeader();
        boolean canUpload = selectedHeader != null && isCleanSnapshot(invUpIn.getStackInSlot(0))
            && invUpOut.getStackInSlot(0).isEmpty();
        if (canUpload) {
            if (progressUp == -1) {
                progressUp = 0;
            }
            if (progressUp >= TRANSFER_TIME) {
                Snapshot cached = GlobalSavedDataSnapshots.getSnapshotForConstruction(level, selected);
                if (cached != null && sameContentKey(cached.key, selected)) {
                    finishUpload(cached, selectedHeader);
                } else {
                    sendNetworkGuiUpdate(NET_UP);
                }
                progressUp = -1;
            } else {
                progressUp++;
            }
        } else if (progressUp != -1) {
            progressUp = -1;
        }
    }

    public double getDownloadProgress(float partialTicks) {
        return interpolateProgress(progressDownLast, progressDown, partialTicks);
    }

    public double getUploadProgress(float partialTicks) {
        return interpolateProgress(progressUpLast, progressUp, partialTicks);
    }

    private static double interpolateProgress(int last, int current, float partialTicks) {
        if (current < 0) {
            return 0;
        }
        if (last < 0) {
            last = current;
        }
        double value = last * (1.0D - partialTicks) + current * partialTicks;
        if (value <= 0) {
            return 0;
        }
        if (value >= TRANSFER_TIME) {
            return 1;
        }
        return value / TRANSFER_TIME;
    }

    // Downloading writes only to the player's local blueprint library.
    // Uploading first reuses content already available to the construction side; if it is missing, the server
    // requests the selected local blueprint and caches the content before producing the used blueprint item.

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                buffer.writeBoolean(selected != null);
                if (selected != null) {
                    selected.writeToByteBuf(buffer);
                }
            }
            if (id == NET_DOWN) {
                Snapshot.Header header = ItemSnapshot.getHeader(invDownIn.getStackInSlot(0));
                Snapshot snapshot = getSnapshotForDownload();
                if (header != null && snapshot != null) {
                    snapshot = snapshot.copy();
                    snapshot.key = new Snapshot.Key(snapshot.key, header);
                    buffer.writeBoolean(true);
                    NbtSquisher.squish(
                        Snapshot.writeToNBT(snapshot),
                        NbtSquishConstants.BUILDCRAFT_V1_COMPRESSED,
                        buffer
                    );
                } else {
                    buffer.writeBoolean(false);
                }
            }
            // noinspection StatementWithEmptyBody
            if (id == NET_UP) {
            }
            if (id == NET_GUI_DATA || (id == NET_GUI_TICK && (progressDown != progressDownLast || progressUp != progressUpLast))) {
                buffer.writeVarInt(progressDown);
                buffer.writeVarInt(progressUp);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                if (buffer.readBoolean()) {
                    selected = new Snapshot.Key(buffer);
                } else {
                    selected = null;
                }
            }
            if (id == NET_DOWN) {
                if (buffer.readBoolean()) {
                    Snapshot snapshot = Snapshot.readFromNBT(NbtSquisher.expand(buffer));
                    snapshot.computeKey();
                    GlobalSavedDataSnapshots.saveClientSnapshot(snapshot);
                }
            }
            if (id == NET_GUI_TICK || id == NET_GUI_DATA) {
                progressDownLast = progressDown;
                progressUpLast = progressUp;
                progressDown = buffer.readVarInt();
                progressUp = buffer.readVarInt();
            }
            if (id == NET_UP) {
                if (selected != null) {
                    Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(selected);
                    if (snapshot != null) {
                        try (OutputStream outputStream = new OutputStream() {
                            private byte[] buf = new byte[4 * 1024];
                            private int pos = 0;
                            private boolean closed = false;
                            
                            private void write(boolean last) throws IOException {
                                BCNetwork.sendToServer(createMessage(NET_UP, localBuffer -> {
                                    selected.writeToByteBuf(localBuffer);
                                    localBuffer.writeBoolean(last);
                                    localBuffer.writeByteArray(buf);
                                }));
                            }

                            @Override
                            public void write(int b) throws IOException {
                                buf[pos++] = (byte) b;
                                if (pos >= buf.length) {
                                    write(false);
                                    buf = new byte[buf.length];
                                    pos = 0;
                                }
                            }

                            @Override
                            public void close() throws IOException {
                                if (closed) {
                                    return;
                                }
                                closed = true;
                                buf = Arrays.copyOf(buf, pos);
                                pos = 0;
                                write(true);
                            }
                        }) {
                            NbtSquisher.squish(
                                Snapshot.writeToNBT(snapshot),
                                NbtSquishConstants.BUILDCRAFT_V1_COMPRESSED,
                                outputStream
                            );
                        }
                    }
                }
            }
        }
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_UP) {
                ServerPlayer sender = ctx == null ? null : ctx.getSender();
                if (sender == null) {
                    throw new DecoderException("Electronic-library upload has no server sender");
                }
                if (!(sender.containerMenu instanceof ContainerElectronicLibrary menu)
                    || menu.tile != this || !menu.stillValid(sender)) {
                    throw new DecoderException("Electronic-library upload does not match the sender's open library menu");
                }

                UUID playerId = sender.getUUID();
                Snapshot.Key key = new Snapshot.Key(buffer);
                Pair<UUID, Snapshot.Key> pair = Pair.of(playerId, key);
                boolean last = buffer.readBoolean();

                try {
                    // Owner identity is deliberately irrelevant here. The actual sender only needs to be
                    // interacting with this library; ownership in BuildCraft is attribution for automated
                    // actions, not an ACL that makes machines private.
                    if (selected == null || !selected.equals(key)
                        || !isCleanSnapshot(invUpIn.getStackInSlot(0))
                        || !invUpOut.getStackInSlot(0).isEmpty()) {
                        throw new DecoderException("Electronic-library upload no longer matches the active transfer");
                    }

                    int partLength = NetworkSecurity.requireCount(
                        buffer.readVarInt(), MAX_UPLOAD_PART_BYTES, "electronic-library upload part bytes"
                    );
                    if (!last && partLength == 0) {
                        throw new DecoderException("Electronic-library upload contains an empty non-final part");
                    }
                    NetworkSecurity.requireReadable(buffer, partLength, "electronic-library upload part");
                    byte[] part = new byte[partLength];
                    buffer.readBytes(part);

                    // A client may have only one in-flight upload for this library. Changing selection
                    // abandons the partial transfer state rather than retaining attacker-controlled buffers.
                    upSnapshotsParts.entrySet().removeIf(entry ->
                        entry.getKey().getLeft().equals(playerId) && !entry.getKey().equals(pair)
                    );
                    if (!upSnapshotsParts.containsKey(pair) && upSnapshotsParts.size() >= MAX_CONCURRENT_UPLOADS) {
                        throw new DecoderException("Too many concurrent electronic-library uploads");
                    }

                    UploadAccumulator upload = upSnapshotsParts.computeIfAbsent(pair, ignored -> new UploadAccumulator());
                    upload.add(part);
                    if (!last) {
                        return;
                    }

                    Snapshot snapshot = Snapshot.readFromNBT(NbtSquisher.expandBuildCraftV1Limited(
                        upload.join(), MAX_UPLOAD_EXPANDED_BYTES, MAX_UPLOAD_NBT_DECODE_BUDGET
                    ));
                    Snapshot.Header header = snapshot.key.header;
                    Snapshot.Header selectedHeader = getSelectedHeader();
                    if (header == null || selectedHeader == null) {
                        throw new DecoderException("Electronic-library upload has no valid snapshot header");
                    }
                    if (!header.equals(selectedHeader)) {
                        throw new DecoderException("Electronic-library upload header does not match the selected blueprint");
                    }
                    snapshot = snapshot.copy();
                    snapshot.computeKey();
                    if (!sameContentKey(snapshot.key, selected) || !sameContentKey(snapshot.key, header.key)) {
                        throw new DecoderException("Electronic-library upload content does not match the selected blueprint");
                    }
                    GlobalSavedDataSnapshots.cacheServerSnapshot(level, snapshot);
                    finishUpload(snapshot, selectedHeader);
                } catch (IOException | RuntimeException e) {
                    upSnapshotsParts.remove(pair);
                    throw e;
                } finally {
                    if (last) {
                        upSnapshotsParts.remove(pair);
                    }
                }
            }
        }
    }

    private static final class UploadAccumulator {
        private final List<byte[]> parts = new ArrayList<>();
        private int totalBytes;

        private void add(byte[] part) {
            if (parts.size() >= MAX_UPLOAD_PARTS) {
                throw new DecoderException("Electronic-library upload has too many parts");
            }
            if (part.length > MAX_UPLOAD_TOTAL_BYTES - totalBytes) {
                throw new DecoderException("Electronic-library upload exceeds compressed size limit");
            }
            parts.add(part);
            totalBytes += part.length;
        }

        private byte[] join() {
            return Bytes.concat(parts.toArray(new byte[0][]));
        }
    }

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new ContainerElectronicLibrary(id, inv, invDownOut, invDownIn, invUpIn, invUpOut, ContainerLevelAccess.create(level, worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return getBlockState().getBlock().getName();
	}
}
