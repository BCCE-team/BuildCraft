/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import net.minecraft.core.HolderLookup;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import buildcraft.robotics.zone.ZoneTransferCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.area.IZone;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.map.MapLocationKind;
import buildcraft.api.v2.map.MapLocationView;
import java.util.Optional;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.delta.DeltaInt;
import buildcraft.lib.delta.DeltaManager.EnumNetworkVisibility;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.robotics.BCRoboticsBlocks;
import buildcraft.robotics.container.ContainerZonePlanner;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import buildcraft.lib.net.BCPacketContext;

public class TileZonePlanner extends TileBC_Neptune implements IDebuggable, MenuProvider {
    public static final int MAX_MAP_NAME_LENGTH = 64;
    private final Map<UUID, LayerSend> outboundLayers = new LinkedHashMap<>();
    private final ZoneTransferCodec.Assembler inboundLayers = new ZoneTransferCodec.Assembler();
    private int zoneRevision;
    private int clientRevision = -1;

    private static final class LayerSend {
        int layer;
        int offset;
        byte[] content;
        final int revision;
        LayerSend(int revision) { this.revision = revision; }
    }

    protected static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("zone_planner");
    private static final int NET_ZONE_REQUEST = IDS.allocId("zone_layers_request");
    private static final int NET_ZONE_PART = IDS.allocId("zone_layers_part");

    public static final int LAYER_COUNT = 16;
    public static final int RESOLUTION = 2048;
    public static final int CRAFT_TIME = 120;

    private static final int SLOT_INPUT_MAP = 0;
    private static final int SLOT_OUTPUT_MAP = 1;
    private static final int SLOT_IMPORT_MAP = 2;

    public final ItemHandlerSimple inv = itemManager.addInvHandler(
            "inv",
            3,
            TileZonePlanner::isItemValid,
            EnumAccess.NONE
    );

    public int progress = 0;
    public final DeltaInt deltaProgress = deltaManager.addDelta("progress", EnumNetworkVisibility.GUI_ONLY);
    public String mapName = "";

    public final ZonePlan[] layers = new ZonePlan[LAYER_COUNT];
    private int currentSelectedArea = 0;

    public TileZonePlanner(BlockPos pos, BlockState state) {
        super(BCRoboticsBlocks.ZONE_PLANNER_TILE.get(), pos, state);
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new ZonePlan();
        }
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    private static boolean isItemValid(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        return switch (slot) {
            case SLOT_INPUT_MAP, SLOT_IMPORT_MAP -> BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(stack).isPresent();
            case SLOT_OUTPUT_MAP -> false;
            default -> false;
        };
    }

    @Override
    public void update() {
        deltaManager.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        flushZoneSync();


        ItemStack input = inv.getStackInSlot(SLOT_INPUT_MAP);
        ItemStack output = inv.getStackInSlot(SLOT_OUTPUT_MAP);
        boolean canCraft = !input.isEmpty() && output.isEmpty()
                && BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(input).isPresent();

        if (!canCraft) {
            if (progress != 0) {
                progress = 0;
                deltaProgress.setValue(0);
            }
            return;
        }

        if (progress == 0) {
            deltaProgress.addDelta(0, CRAFT_TIME, 1);
            deltaProgress.addDelta(CRAFT_TIME, CRAFT_TIME + 5, -1);
        }

        if (progress < CRAFT_TIME) {
            progress++;
            return;
        }

        ZonePlan selected = selectArea(currentSelectedArea);
        MapLocationView view = new MapLocationView(
            MapLocationKind.ZONE, mapName, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new ZonePlan(selected)), Optional.empty()
        );
        var mapLocations = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS);
        ItemStack simulated = input.copy();
        simulated.setCount(1);
        if (!mapLocations.write(simulated, view, OperationMode.SIMULATE)) {
            progress = 0;
            deltaProgress.setValue(0);
            return;
        }

        ItemStack inputBefore = input.copy();
        ItemStack crafted = inv.extractItem(SLOT_INPUT_MAP, 1, false);
        if (crafted.isEmpty()) {
            progress = 0;
            deltaProgress.setValue(0);
            return;
        }
        if (!mapLocations.write(crafted, view, OperationMode.EXECUTE)) {
            // A third-party adapter is allowed to reject EXECUTE even after a successful simulation.
            // Restore the exact pre-transaction input so a failed adapter can never consume the map.
            inv.setStackInSlot(SLOT_INPUT_MAP, inputBefore);
            progress = 0;
            deltaProgress.setValue(0);
            setChanged();
            return;
        }

        inv.setStackInSlot(SLOT_OUTPUT_MAP, crafted);
        progress = 0;
        deltaProgress.setValue(0);
        setChanged();
        zoneRevision++;
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, @Nonnull ItemStack before,
            @Nonnull ItemStack after) {
        if (level != null && !level.isClientSide && handler == inv && slot == SLOT_IMPORT_MAP) {
            importMap(after);
        }
        if (level != null) {
            super.onSlotChange(handler, slot, before, after);
        }
    }

    private void importMap(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        var location = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).read(stack);
        if (location.isEmpty()) return;
        var zone = location.get().zone().orElse(null);
        if (zone instanceof ZonePlan plan) {
            // Imported plans obey the same loaded-chunk boundary as plans drawn in the GUI.
            setArea(currentSelectedArea, plan);
        }
    }

    public ZonePlan selectArea(int index) {
        if (index < 0 || index >= layers.length) {
            index = 0;
        }
        if (layers[index] == null) {
            layers[index] = new ZonePlan();
        }
        currentSelectedArea = index;
        return layers[index];
    }

    public void setArea(int index, ZonePlan area) {
        if (index < 0 || index >= layers.length) {
            return;
        }
        ZonePlan requested = area == null ? new ZonePlan() : area;
        if (level instanceof ServerLevel serverLevel) {
            layers[index] = requested.copyChunksMatching(chunkPos ->
                    serverLevel.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) != null);
        } else {
            layers[index] = new ZonePlan(requested);
        }
        setChanged();
        zoneRevision++;
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    public int getCurrentSelectedArea() {
        return currentSelectedArea;
    }

    public void setMapName(String mapName) {
        String clean = mapName == null ? "" : mapName;
        this.mapName = clean.length() <= MAX_MAP_NAME_LENGTH ? clean : clean.substring(0, MAX_MAP_NAME_LENGTH);
        setChanged();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER && id == NET_RENDER_DATA) {
            buffer.writeUtf(mapName, MAX_MAP_NAME_LENGTH);
            buffer.writeByte(currentSelectedArea);
            buffer.writeInt(zoneRevision);
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER && id == NET_ZONE_REQUEST) {
            int requestedRevision = buffer.readInt();
            ServerPlayer player = ctx == null ? null : ctx.getSender();
            if (player != null && requestedRevision == zoneRevision
                    && player.distanceToSqr(worldPosition.getX() + 0.5,
                        worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64 * 64) {
                outboundLayers.put(player.getUUID(), new LayerSend(zoneRevision));
            }
        }
        if (side == BCNetworkSide.CLIENT && id == NET_ZONE_PART) {
            int revision = buffer.readInt();
            int layerIndex = buffer.readUnsignedByte();
            int total = buffer.readInt();
            int offset = buffer.readInt();
            byte[] bytes = buffer.readByteArray(ZoneTransferCodec.PART_BYTES);
            if (revision == clientRevision) {
                ZonePlan completed = inboundLayers.accept(revision, layerIndex, total, offset, bytes);
                if (completed != null && layerIndex < layers.length) layers[layerIndex] = completed;
            }
        }
        if (side == BCNetworkSide.CLIENT && id == NET_RENDER_DATA) {
            mapName = buffer.readUtf(MAX_MAP_NAME_LENGTH);
            currentSelectedArea = buffer.readUnsignedByte();
            int newRevision = buffer.readInt();
            if (clientRevision != newRevision) {
                clientRevision = newRevision;
                inboundLayers.reset();
                if (level != null) {
                    createAndSendMessage(NET_ZONE_REQUEST, out -> out.writeInt(newRevision));
                }
            }
        }
    }

    /** At most four 8 KiB payloads per server tick for all watching requesters. */
    private void flushZoneSync() {
        if (!(level instanceof ServerLevel server) || outboundLayers.isEmpty()) return;
        int remaining = 4;
        var iterator = outboundLayers.entrySet().iterator();
        while (iterator.hasNext() && remaining > 0) {
            var entry = iterator.next();
            ServerPlayer player = server.getServer().getPlayerList().getPlayer(entry.getKey());
            LayerSend send = entry.getValue();
            if (player == null || player.distanceToSqr(worldPosition.getX() + 0.5,
                    worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > 64 * 64
                    || send.revision != zoneRevision) {
                iterator.remove();
                continue;
            }
            if (send.layer >= layers.length) { iterator.remove(); continue; }
            try {
                if (send.content == null) send.content = ZoneTransferCodec.encode(layers[send.layer]);
            } catch (RuntimeException invalidLayer) {
                // Never emit a partial or oversized layer: the client keeps its previous valid plan.
                iterator.remove();
                continue;
            }
            byte[] part = ZoneTransferCodec.part(send.content, send.offset);
            int layerIndex = send.layer;
            int total = send.content.length;
            int offset = send.offset;
            createAndSendMessage(NET_ZONE_PART, player, out -> {
                out.writeInt(send.revision);
                out.writeByte(layerIndex);
                out.writeInt(total);
                out.writeInt(offset);
                out.writeByteArray(part);
            });
            remaining--;
            send.offset += part.length;
            if (send.offset == total) {
                send.layer++;
                send.offset = 0;
                send.content = null;
                if (send.layer >= layers.length) iterator.remove();
            }
        }
    }

    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        progress = bcData.readInt("progress");
        mapName = bcData.readString("name");
        currentSelectedArea = bcData.readInt("currentSelectedArea");
        if (currentSelectedArea < 0 || currentSelectedArea >= layers.length) {
            currentSelectedArea = 0;
        }
        if (mapName == null) {
            mapName = "";
        }
        for (int i = 0; i < layers.length; i++) {
            layers[i].readFromNBT(bcData.readCompound("selectedArea[" + i + "]"));
            if (layers[i].getChunkPoses().isEmpty() && bcData.has("layer_" + i)) {
                // Compatibility with BC8-style Zone Planner layer keys in existing saves.
                layers[i].readFromNBT(bcData.readCompound("layer_" + i));
            }
        }
    }

    @Override
    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        super.writeData(bcData);
        bcData.writeInt("progress", progress);
        bcData.writeString("name", mapName);
        bcData.writeInt("currentSelectedArea", currentSelectedArea);
        for (int i = 0; i < layers.length; i++) {
            CompoundTag layerTag = new CompoundTag();
            (layers[i] == null ? new ZonePlan() : layers[i]).writeToNBT(layerTag);
            nbt.put("selectedArea[" + i + "]", layerTag);
        }
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerZonePlanner(id, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("progress = " + progress);
        left.add("selected_area = " + currentSelectedArea);
        left.add("map_name = " + mapName);
    }
}
