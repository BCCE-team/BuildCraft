package buildcraft.lib.internal.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;

/** Shared, safe vanilla/registry paint fallback. Custom handlers still take precedence. */
public enum CustomPaintHelper {
    INSTANCE;

    private static final boolean DEBUG = BCDebugging.shouldDebugLog("api.painting");
    protected static final Map<Class<? extends Block>, String> RECOLOR_MAP = new HashMap<>();
    private final Map<Class<? extends Block>, List<ICustomPaintHandler>> handlers = Maps.newIdentityHashMap();
    private final List<ICustomPaintHandler> allHandlers = Lists.newArrayList();

    static {
        RECOLOR_MAP.put(GlazedTerracottaBlock.class, "_glazed_terracotta");
        RECOLOR_MAP.put(CandleBlock.class, "_candle");
        RECOLOR_MAP.put(CandleCakeBlock.class, "_candle_cake");
        RECOLOR_MAP.put(StainedGlassBlock.class, "_stained_glass");
        RECOLOR_MAP.put(StainedGlassPaneBlock.class, "_stained_glass_pane");
        RECOLOR_MAP.put(WoolCarpetBlock.class, "_carpet");
        RECOLOR_MAP.put(BannerBlock.class, "_banner");
        RECOLOR_MAP.put(WallBannerBlock.class, "_wall_banner");
        RECOLOR_MAP.put(BedBlock.class, "_bed");
        RECOLOR_MAP.put(ShulkerBoxBlock.class, "_shulker_box");
        RECOLOR_MAP.put(ConcretePowderBlock.class, "_concrete_powder");
    }

    public void registerHandlerForAll(ICustomPaintHandler handler) {
        if (DEBUG) BCLog.logger.info("[api.painting] Adding handler for all blocks: " + handler.getClass());
        allHandlers.add(handler);
    }

    public void registerHandler(Class<? extends Block> block, ICustomPaintHandler handler) {
        if (DEBUG) BCLog.logger.info("[api.painting] Adding handler for " + block + ": " + handler.getClass());
        handlers.computeIfAbsent(block, key -> Lists.newArrayList()).add(handler);
    }

    public InteractionResult attemptPaintBlock(Level world, BlockPos pos, BlockState state, Vec3 hitPos,
            @Nullable Direction hitSide, @Nullable DyeColor paint) {
        Block block = state.getBlock();
        if (block instanceof ICustomPaintHandler special) {
            InteractionResult result = special.attemptPaint(world, pos, state, hitPos, hitSide, paint);
            if (result != InteractionResult.PASS) return result;
        }
        List<ICustomPaintHandler> custom = handlers.get(block.getClass());
        if (custom != null) {
            for (ICustomPaintHandler handler : custom) {
                InteractionResult result = handler.attemptPaint(world, pos, state, hitPos, hitSide, paint);
                if (result != InteractionResult.PASS) return result;
            }
        }
        for (ICustomPaintHandler handler : allHandlers) {
            InteractionResult result = handler.attemptPaint(world, pos, state, hitPos, hitSide, paint);
            if (result != InteractionResult.PASS) return result;
        }
        return recolorBlock(world, pos, paint) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    /** External mods can register an uncolored/color-family suffix for a block class. */
    public static void registryRecolor(Class<? extends Block> block, String suffix) {
        RECOLOR_MAP.put(block, suffix);
    }

    /**
     * Resolve the actual block material, without touching the world. Also used by facade brushes.
     * Retain all compatible properties (facing, occupied, rotation, waterlogging, etc.).
     * If the destination variant does not exist, never substitute air.
     */
    @Nullable
    public static BlockState recoloredState(BlockState source, @Nullable DyeColor paint) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(source.getBlock());
        if (key == null) return null;
        String path = key.getPath();
        String suffix = null;
        for (DyeColor existing : DyeColor.values()) {
            String prefix = existing.getName() + "_";
            if (path.startsWith(prefix)) {
                suffix = path.substring(prefix.length());
                break;
            }
        }
        if (suffix == null) {
            suffix = switch (path) {
                case "glass" -> "stained_glass";
                case "glass_pane" -> "stained_glass_pane";
                case "terracotta", "candle", "candle_cake", "shulker_box" -> path;
                default -> null;
            };
        }
        if (suffix == null) {
            String registered = RECOLOR_MAP.get(source.getBlock().getClass());
            if (registered != null && !registered.isEmpty()) {
                suffix = registered.startsWith("_") ? registered.substring(1) : registered;
            }
        }
        if (suffix == null || suffix.isEmpty()) return null;

        String destination;
        if (paint != null) {
            destination = paint.getName() + "_" + suffix;
        } else {
            destination = switch (suffix) {
                case "stained_glass" -> "glass";
                case "stained_glass_pane" -> "glass_pane";
                case "candle", "candle_cake", "terracotta", "shulker_box" -> suffix;
                default -> null;
            };
            if (destination == null) return null;
        }
        //? if >=1.21.11 {
        Block target = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), destination))
            .map(net.minecraft.core.Holder.Reference::value).orElse(Blocks.AIR);
        //?} else {
        Block target = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), destination));
        //?}
        if (target == null || target == Blocks.AIR || target == source.getBlock()) return null;
        BlockState result = target.defaultBlockState();
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) result = transferProperty(source, result, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState transferProperty(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    /** Recolor world blocks only on the server, with block-entity data and multi-block structure preserved. */
    public static boolean recolorBlock(Level world, BlockPos pos, @Nullable DyeColor paint) {
        BlockState original = world.getBlockState(pos);
        BlockState colored = recoloredState(original, paint);
        if (colored == null) return false;
        if (world.isClientSide()) return true;

        if (original.getBlock() instanceof BedBlock) {
            return recolorBed(world, pos, original, colored, paint);
        }

        BlockEntity previous = world.getBlockEntity(pos);
        if (previous != null && !colored.hasBlockEntity()) return false;
        CompoundTag saved = previous == null ? null : previous.saveWithFullMetadata(world.registryAccess());
        // 16 defers neighbor shape updates until all data has been transferred;
        // 32 prevents drops from a color-only replacement.
        if (!world.setBlock(pos, colored, 2 | 16 | 32)) return false;
        if (saved != null) {
            BlockEntity replacement = world.getBlockEntity(pos);
            if (replacement == null) {
                world.setBlock(pos, original, 2 | 16 | 32);
                BlockEntity reverted = world.getBlockEntity(pos);
                //? if >=1.21.11 {
                if (reverted != null) reverted.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, world.registryAccess(), saved));
                //?} else {
                if (reverted != null) reverted.loadWithComponents(saved, world.registryAccess());
                //?}
                return false;
            }
            //? if >=1.21.11 {
            replacement.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, world.registryAccess(), saved));
            //?} else {
            replacement.loadWithComponents(saved, world.registryAccess());
            //?}
            replacement.setChanged();
        }
        world.updateNeighborsAt(pos, colored.getBlock());
        world.sendBlockUpdated(pos, original, colored, 3);
        return true;
    }

    private static boolean recolorBed(Level world, BlockPos pos, BlockState original, BlockState colored,
            @Nullable DyeColor paint) {
        Direction facing = original.getValue(BedBlock.FACING);
        BedPart part = original.getValue(BedBlock.PART);
        BlockPos partnerPos = pos.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
        BlockState partner = world.getBlockState(partnerPos);
        if (!(partner.getBlock() instanceof BedBlock) || partner.getBlock() != original.getBlock()
                || partner.getValue(BedBlock.FACING) != facing || partner.getValue(BedBlock.PART) == part) {
            return false;
        }
        BlockState coloredPartner = recoloredState(partner, paint);
        if (coloredPartner == null || coloredPartner.getBlock() != colored.getBlock()) return false;
        // Commit both halves without intermediate neighbor shape notifications.
        if (!world.setBlock(pos, colored, 2 | 16 | 32)) return false;
        if (!world.setBlock(partnerPos, coloredPartner, 2 | 16 | 32)) {
            world.setBlock(pos, original, 2 | 16 | 32);
            return false;
        }
        world.updateNeighborsAt(pos, colored.getBlock());
        world.updateNeighborsAt(partnerPos, colored.getBlock());
        world.sendBlockUpdated(pos, original, colored, 3);
        world.sendBlockUpdated(partnerPos, partner, coloredPartner, 3);
        return true;
    }
}
