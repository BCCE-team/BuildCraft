/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.tile;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.platform.registry.PlatformMenus;
import buildcraft.lib.platform.storage.MutableItemStorage;
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
import buildcraft.lib.net.BCPacketContext;

public class TileZonePlanner extends TileBC_Neptune implements IDebuggable, MenuProvider {
    public static final int MAX_MAP_NAME_LENGTH = 64;
    protected static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("zone_planner");

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
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    @Override
    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before,
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
            for (ZonePlan layer : layers) {
                (layer == null ? new ZonePlan() : layer).writeToByteBuf(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT && id == NET_RENDER_DATA) {
            mapName = buffer.readUtf(MAX_MAP_NAME_LENGTH);
            currentSelectedArea = buffer.readUnsignedByte();
            for (int i = 0; i < layers.length; i++) {
                layers[i].readFromByteBuf(buffer);
            }
        }
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        progress = nbt.getInt("progress");
        mapName = nbt.getString("name");
        currentSelectedArea = nbt.getInt("currentSelectedArea");
        if (currentSelectedArea < 0 || currentSelectedArea >= layers.length) {
            currentSelectedArea = 0;
        }
        if (mapName == null) {
            mapName = "";
        }
        for (int i = 0; i < layers.length; i++) {
            layers[i].readFromNBT(nbt.getCompound("selectedArea[" + i + "]"));
            if (layers[i].getChunkPoses().isEmpty() && nbt.contains("layer_" + i)) {
                // Compatibility with BC8-style Zone Planner layer keys in existing saves.
                layers[i].readFromNBT(nbt.getCompound("layer_" + i));
            }
        }
    }

    @Override
    public void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        nbt.putInt("progress", progress);
        nbt.putString("name", mapName);
        nbt.putInt("currentSelectedArea", currentSelectedArea);
        for (int i = 0; i < layers.length; i++) {
            CompoundTag layerTag = new CompoundTag();
            (layers[i] == null ? new ZonePlan() : layers[i]).writeToNBT(layerTag);
            nbt.put("selectedArea[" + i + "]", layerTag);
        }
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PlatformMenus.open(serverPlayer, this, worldPosition);
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
