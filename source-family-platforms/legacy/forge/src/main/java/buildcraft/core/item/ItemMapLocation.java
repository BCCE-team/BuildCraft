/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.item;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.area.IAreaProvider;
import buildcraft.lib.internal.area.IBox;
import buildcraft.lib.internal.area.IPathProvider;
import buildcraft.lib.internal.area.IZone;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.area.BlockBox;
import buildcraft.api.v2.area.Path;
import buildcraft.api.v2.area.Zone;
import buildcraft.api.v2.item.ItemLabelAdapter;
import buildcraft.api.v2.map.MapLocationAdapter;
import buildcraft.api.v2.map.MapLocationKind;
import buildcraft.api.v2.map.MapLocationView;
import java.util.Optional;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.StringUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.robotics.zone.ZonePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class ItemMapLocation extends Item implements MapLocationAdapter, ItemLabelAdapter {
    private static final String[] STORAGE_TAGS = "kind,Damage,x,y,z,side,xMin,xMax,yMin,yMax,zMin,zMax,path,chunkMapping,name".split(",");

    public ItemMapLocation(Item.Properties prop) {
        super(prop);
    }
    
    @Override
	public int getMaxStackSize(ItemStack stack) {
    	return MapLocationType.getFromStack(stack) == MapLocationType.CLEAN ? 16 : 1;
	}

    @OnlyIn(Dist.CLIENT)
    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> strings, TooltipFlag flag) {
        stack = StackUtil.asNonNull(stack);
        CompoundTag cpt = stack.getOrCreateTag();
        if (cpt.contains("name")) {
            String name = cpt.getString("name");
            if (name.length() > 0) {
                strings.add(Component.literal(name));
            }
        }

        MapLocationType type = MapLocationType.getFromStack(stack);
        switch (type) {
            case SPOT: {
                if (cpt.contains("x") && cpt.contains("y") && cpt.contains("z") && cpt.contains("side")) {
                    int x = cpt.getInt("x");
                    int y = cpt.getInt("y");
                    int z = cpt.getInt("z");
                    Direction side = getPointFace(stack);
                    strings.add(Component.literal("{" + x + ", " + y + ", " + z + ", " + side + "}"));
                }
                break;
            }
            case AREA: {
                if (cpt.contains("xMin") && cpt.contains("yMin") && cpt.contains("zMin") && cpt.contains("xMax")
                    && cpt.contains("yMax") && cpt.contains("zMax")) {
                    int x = cpt.getInt("xMin");
                    int y = cpt.getInt("yMin");
                    int z = cpt.getInt("zMin");
                    int xLength = cpt.getInt("xMax") - x + 1;
                    int yLength = cpt.getInt("yMax") - y + 1;
                    int zLength = cpt.getInt("zMax") - z + 1;

                    strings.add(Component.literal(
                        "{" + x + ", " + y + ", " + z + "} + {" + xLength + " x " + yLength + " x " + zLength + "}"));
                }
                break;
            }
            case PATH:
            case PATH_REPEATING: {
                if (cpt.contains("path")) {
                    ListTag pathNBT = (ListTag) cpt.get("path");
                    if (pathNBT.size() > 0) {
                        BlockPos first = NBTUtilBC.readBlockPos(pathNBT.get(0));
                        if (first != null) {
                            strings.add(Component.literal("{"+
                                StringUtilBC.blockPosToString(first) + "}, (+" + (pathNBT.size() - 1) + " elements)"));
                        }
                    }
                }
                break;
            }
            default: {
                break;
            }
        }
        if (type != MapLocationType.CLEAN) {
            strings.add(Component.translatable("buildcraft.item.nonclean.usage"));
        }
    }


	@Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (player.isShiftKeyDown()) {
            return clearMarkerData(stack);
        }
        return new InteractionResultHolder<>(InteractionResult.PASS, stack);
    }

    private static InteractionResultHolder<ItemStack> clearMarkerData(@Nonnull ItemStack stack) {
        if (MapLocationType.getFromStack(stack) == MapLocationType.CLEAN) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        CompoundTag nbt = stack.getOrCreateTag();
        for (String key : STORAGE_TAGS) {
            nbt.remove(key);
        }
        if (nbt.isEmpty()) {
            stack.setTag(null);
        }
        MapLocationType.CLEAN.setToStack(stack);
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        Level world = ctx.getLevel();
        if (world.isClientSide) {
            return InteractionResult.PASS;
        }

        Player player = ctx.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            return clearMarkerData(stack).getResult();
        }

        return writeLocationToMap(ctx, StackUtil.asNonNull(stack));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level world = ctx.getLevel();
        if (world.isClientSide) {
            return InteractionResult.PASS;
        }

        Player player = ctx.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            return clearMarkerData(StackUtil.asNonNull(ctx.getItemInHand())).getResult();
        }

        return writeLocationToMap(ctx, StackUtil.asNonNull(ctx.getItemInHand()));
    }

    private static InteractionResult writeLocationToMap(UseOnContext ctx, ItemStack stack) {
        Level world = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();

        if (MapLocationType.getFromStack(stack) != MapLocationType.CLEAN) {
            return InteractionResult.FAIL;
        }

        ItemStack modified = stack;
        boolean splitFromStack = player != null && stack.getCount() > 1 && !player.getAbilities().instabuild;
        if (splitFromStack) {
            modified = stack.copy();
            modified.setCount(1);
            stack.shrink(1);
        }

        BlockEntity tile = world.getBlockEntity(pos);
        CompoundTag cpt = modified.getOrCreateTag();

        if (tile instanceof IPathProvider pathTile) {
            List<BlockPos> path = pathTile.getPath();
            if (path.size() > 1 && path.get(0).equals(path.get(path.size() - 1))) {
                MapLocationType.PATH_REPEATING.setToStack(modified);
            } else {
                MapLocationType.PATH.setToStack(modified);
            }

            ListTag pathNBT = new ListTag();
            for (BlockPos posInPath : path) {
                pathNBT.add(NbtUtils.writeBlockPos(posInPath));
            }
            cpt.put("path", pathNBT);
        } else if (tile instanceof IAreaProvider areaTile) {
            MapLocationType.AREA.setToStack(modified);

            cpt.putInt("xMin", areaTile.min().getX());
            cpt.putInt("yMin", areaTile.min().getY());
            cpt.putInt("zMin", areaTile.min().getZ());
            cpt.putInt("xMax", areaTile.max().getX());
            cpt.putInt("yMax", areaTile.max().getY());
            cpt.putInt("zMax", areaTile.max().getZ());
        } else {
            MapLocationType.SPOT.setToStack(modified);

            cpt.putByte("side", (byte) ctx.getClickedFace().get3DDataValue());
            cpt.putInt("x", pos.getX());
            cpt.putInt("y", pos.getY());
            cpt.putInt("z", pos.getZ());
        }

        if (splitFromStack && !modified.isEmpty()) {
            if (!player.getInventory().add(modified)) {
                player.drop(modified, false);
            }
        }

        return InteractionResult.SUCCESS;
    }

    public static IBox getAreaBox(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        int xMin = cpt.getInt("xMin");
        int yMin = cpt.getInt("yMin");
        int zMin = cpt.getInt("zMin");
        BlockPos min = new BlockPos(xMin, yMin, zMin);

        int xMax = cpt.getInt("xMax");
        int yMax = cpt.getInt("yMax");
        int zMax = cpt.getInt("zMax");
        BlockPos max = new BlockPos(xMax, yMax, zMax);

        return new Box(min, max);
    }

    public static IBox getPointBox(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType type = MapLocationType.getFromStack(item);

        switch (type) {
            case SPOT: {
                int x = cpt.getInt("x");
                int y = cpt.getInt("y");
                int z = cpt.getInt("z");

                BlockPos pos = new BlockPos(x, y, z);

                return new Box(pos, pos);
            }
            default: {
                return null;
            }
        }
    }

    public static Direction getPointFace(@Nonnull ItemStack stack) {
        CompoundTag cpt = stack.getOrCreateTag();
        int side = cpt.getByte("side");
        Direction[] values = Direction.values();
        if (side < 0 || side >= values.length) {
            return Direction.UP;
        }
        return values[side];
    }

    public IBox getBox(@Nonnull ItemStack item) {
        MapLocationType type = MapLocationType.getFromStack(item);

        switch (type) {
            case AREA: {
                return getAreaBox(item);
            }
            case SPOT: {
                return getPointBox(item);
            }
            default: {
                return null;
            }
        }
    }

    public Direction getPointSide(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType type = MapLocationType.getFromStack(item);

        if (type == MapLocationType.SPOT) {
            return getPointFace(item);
        } else {
            return null;
        }
    }

    public BlockPos getPoint(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType type = MapLocationType.getFromStack(item);

        if (type == MapLocationType.SPOT) {
            return new BlockPos(cpt.getInt("x"), cpt.getInt("y"), cpt.getInt("z"));
        } else {
            return null;
        }
    }

    public IZone getZone(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType type = MapLocationType.getFromStack(item);
        switch (type) {
            case ZONE: {
                ZonePlan plan = new ZonePlan();
                plan.readFromNBT(cpt);
                return plan;
            }
            case AREA: {
                return getBox(item);
            }
            case SPOT:
            case PATH:
            case PATH_REPEATING: {
                return getPointBox(item);
            }
            default: {
                return null;
            }
        }
    }

    public List<BlockPos> getPath(@Nonnull ItemStack item) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType type = MapLocationType.getFromStack(item);
        switch (type) {
            case PATH:
            case PATH_REPEATING: {
                List<BlockPos> indexList = new ArrayList<>();
                if (!(cpt.get("path") instanceof ListTag pathNBT)) {
                    return indexList;
                }
                for (int i = 0; i < pathNBT.size(); i++) {
                    BlockPos pos = NBTUtilBC.readBlockPos(pathNBT.get(i));
                    if (pos != null) {
                        indexList.add(pos);
                    }
                }
                return indexList;
            }
            case SPOT: {
                List<BlockPos> indexList = new ArrayList<>();
                indexList.add(new BlockPos(cpt.getInt("x"), cpt.getInt("y"), cpt.getInt("z")));
                return indexList;
            }
            default: {
                return null;
            }
        }
    }

    private static CompoundTag getMapData(ItemStack stack) {
        return stack.getOrCreateTag();
    }

    private static void setMapData(ItemStack stack, CompoundTag data) {
        stack.setTag(data.isEmpty() ? null : data);
    }

    @Override
    public boolean supports(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == this;
    }

    @Override
    public Optional<MapLocationView> read(ItemStack stack) {
        if (!supports(stack)) return Optional.empty();
        ItemStack snapshot = stack.copy();
        MapLocationType type = MapLocationType.getFromStack(snapshot);
        String label = getLabelName(snapshot);
        if (type == MapLocationType.CLEAN) return Optional.of(MapLocationView.clean(label));
        BlockPos point = getPoint(snapshot);
        Direction side = getPointSide(snapshot);
        IBox legacyBox = getBox(snapshot);
        IZone legacyZone = getZone(snapshot);
        List<BlockPos> legacyPath = getPath(snapshot);
        Optional<BlockBox> box = legacyBox == null ? Optional.empty() : legacyBox.bounds();
        Optional<Zone> zone = legacyZone == null ? Optional.empty() : Optional.of(legacyZone);
        Optional<Path> path = legacyPath == null ? Optional.empty() : Optional.of(() -> List.copyOf(legacyPath));
        return Optional.of(new MapLocationView(
            MapLocationKind.valueOf(type.name()), label, Optional.ofNullable(point), Optional.ofNullable(side), box, zone, path
        ));
    }

    @Override
    public boolean write(ItemStack stack, MapLocationView location, OperationMode mode) {
        if (!supports(stack) || location == null) return false;
        if (!canEncode(location)) return false;
        if (mode == OperationMode.SIMULATE) return true;
        clearMarkerData(stack);
        CompoundTag data = getMapData(stack);
        switch (location.kind()) {
            case CLEAN -> { }
            case SPOT -> {
                BlockPos pos = location.point().orElseThrow();
                data.putInt("x", pos.getX());
                data.putInt("y", pos.getY());
                data.putInt("z", pos.getZ());
                data.putByte("side", (byte) location.pointSide().orElse(Direction.UP).get3DDataValue());
            }
            case AREA -> {
                BlockBox box = location.box().orElseThrow();
                data.putInt("xMin", box.min().getX()); data.putInt("yMin", box.min().getY()); data.putInt("zMin", box.min().getZ());
                data.putInt("xMax", box.max().getX()); data.putInt("yMax", box.max().getY()); data.putInt("zMax", box.max().getZ());
            }
            case PATH, PATH_REPEATING -> {
                ListTag pathTag = new ListTag();
                for (BlockPos pos : location.path().orElseThrow().points()) pathTag.add(NbtUtils.writeBlockPos(pos));
                data.put("path", pathTag);
            }
            case ZONE -> ((ZonePlan) location.zone().orElseThrow()).writeToNBT(data);
        }
        setMapData(stack, data);
        MapLocationType.valueOf(location.kind().name()).setToStack(stack);
        setLabelName(stack, location.label());
        return true;
    }

    private static boolean canEncode(MapLocationView location) {
        return switch (location.kind()) {
            case CLEAN -> true;
            case SPOT -> location.point().isPresent();
            case AREA -> location.box().isPresent();
            case PATH, PATH_REPEATING -> location.path().isPresent();
            case ZONE -> location.zone().orElse(null) instanceof ZonePlan;
        };
    }

    @Override
    public boolean clear(ItemStack stack, OperationMode mode) {
        if (!supports(stack)) return false;
        if (mode == OperationMode.EXECUTE) clearMarkerData(stack);
        return true;
    }

    @Override
    public String label(ItemStack stack) {
        return getLabelName(stack);
    }

    @Override
    public boolean setLabel(ItemStack stack, String label, OperationMode mode) {
        if (!supports(stack)) return false;
        if (mode == OperationMode.EXECUTE) return setLabelName(stack, label);
        return true;
    }

    public static void setZone(@Nonnull ItemStack item, ZonePlan plan) {
        CompoundTag cpt = item.getOrCreateTag();
        MapLocationType.ZONE.setToStack(item);
        plan.writeToNBT(cpt);
    }

    public String getLabelName(@Nonnull ItemStack item) {
        CompoundTag tag = item.getTag();
        return tag == null ? "" : tag.getString("name");
    }

    public boolean setLabelName(@Nonnull ItemStack item, String name) {
        CompoundTag cpt = item.getOrCreateTag();
        cpt.putString("name", name);
        return true;
    }
}
