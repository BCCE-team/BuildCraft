package buildcraft.robotics.item;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.tile.TileRobotProcessor;
//? if >=1.21.1 {
import buildcraft.lib.misc.ItemStackUtil;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if mc_26_x && (>=26.3) {
//?} else {
import net.minecraft.world.InteractionResultHolder;
//?}
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Player-operated link tool. Shift-use on a processor stores the panel target;
 * shift-use on a robot or pipe-mounted docking station explicitly adds it to
 * that panel. Normal use opens the stored panel without scanning for robots.
 */
public final class ItemRobotController extends Item {
    private static final String DIMENSION = "dimension";
    private static final String BLOCK = "block";

    public ItemRobotController(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        // Localizations are managed by the external BuildCraft-Localizations project.
        return Component.literal("Robot Controller");
    }

    //? if mc_26_x && (>=26.3) {
    private static boolean isClient(Level level) { return level.isClientSide(); }
    //?} else {
    private static boolean isClient(Level level) { return level.isClientSide; }
    //?}

    /** Use the field on 1.19.2 and the accessor on newer Minecraft versions. */
    private static Level playerLevel(Player player) {
        //? if <1.20 {
        return player.level;
        //?} else {
        return player.level();
        //?}
    }

    private static CompoundTag stackData(ItemStack stack) {
        //? if >=1.21.1 {
        return ItemStackUtil.getCustomData(stack);
        //?} else {
        return stack.getOrCreateTag();
        //?}
    }

    private static void saveStackData(ItemStack stack, CompoundTag data) {
        //? if >=1.21.1 {
        ItemStackUtil.setCustomData(stack, data);
        //?} else {
        stack.setTag(data);
        //?}
    }

    private static long readBlock(CompoundTag data) {
        //? if mc_26_x && (>=26.3) {
        return buildcraft.lib.compat.NbtCompat.getLong(data, BLOCK);
        //?} else {
        return data.getLong(BLOCK);
        //?}
    }

    private static String readDimension(CompoundTag data) {
        //? if mc_26_x && (>=26.3) {
        return buildcraft.lib.compat.NbtCompat.getString(data, DIMENSION);
        //?} else {
        return data.getString(DIMENSION);
        //?}
    }

    private static String worldKey(Level level) {
        return level.dimension().location().toString();
    }

    public static boolean matchesPanel(ItemStack stack, TileRobotProcessor panel) {
        if (!(stack.getItem() instanceof ItemRobotController) || panel == null || panel.getLevel() == null)
            return false;
        CompoundTag tag = stackData(stack);
        // The position key is interpreted only if the dimension was written by a bind action.
        return tag.contains(DIMENSION) && readDimension(tag).equals(worldKey(panel.getLevel()))
                && readBlock(tag) == panel.getBlockPos().asLong();
    }

    private static TileRobotProcessor resolvePanel(ItemStack stack, Level level) {
        if (!(stack.getItem() instanceof ItemRobotController)) return null;
        CompoundTag tag = stackData(stack);
        if (!tag.contains(DIMENSION) || !worldKey(level).equals(readDimension(tag))) return null;
        BlockPos pos = BlockPos.of(readBlock(tag));
        // A controller is not a chunk loader; don't load remote chunks just to open a menu.
        if (!level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof TileRobotProcessor panel ? panel : null;
    }

    private static void notice(Player player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }

    public static InteractionResult bindProcessor(ItemStack stack, Player player, TileRobotProcessor panel) {
        Level level = panel.getLevel();
        if (level == null) return InteractionResult.PASS;
        if (!isClient(level)) {
            CompoundTag data = stackData(stack);
            data.putString(DIMENSION, worldKey(level));
            data.putLong(BLOCK, panel.getBlockPos().asLong());
            saveStackData(stack, data);
            notice(player, "Controller linked to panel " + panel.getBlockPos().toShortString());
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult linkRobot(ItemStack stack, Player player, EntityRobotBase robot) {
        if (isClient(playerLevel(player))) return InteractionResult.SUCCESS;
        TileRobotProcessor panel = resolvePanel(stack, playerLevel(player));
        if (panel == null) {
            notice(player, "Bind controller to a loaded control panel first");
            return InteractionResult.FAIL;
        }
        if (panel.linkRobot(robot.getRobotId(), robot.blockPosition())) {
            notice(player, "Robot linked: #" + robot.getRobotId());
        } else {
            notice(player, "Robot already linked or panel is full");
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult linkStation(ItemStack stack, Player player, BlockPos pos, Direction side) {
        if (isClient(playerLevel(player))) return InteractionResult.SUCCESS;
        TileRobotProcessor panel = resolvePanel(stack, playerLevel(player));
        if (panel == null) {
            notice(player, "Bind controller to a loaded control panel first");
            return InteractionResult.FAIL;
        }
        if (RobotManager.registryProvider == null) return InteractionResult.FAIL;
        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(playerLevel(player));
        if (registry == null) return InteractionResult.FAIL;
        DockingStation station = registry.getStation(pos, side);
        // Hit the face occupied by a real station. Never link an arbitrary pipe.
        if (station == null) {
            notice(player, "No robot docking station on that face");
            return InteractionResult.FAIL;
        }
        if (panel.linkStation(pos, side)) {
            notice(player, "Docking station linked: " + pos.toShortString());
        } else {
            notice(player, "Station already linked or panel is full");
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!player.isShiftKeyDown() || !(target instanceof EntityRobotBase robot)) return InteractionResult.PASS;
        return linkRobot(stack, player, robot);
    }

    // Use-first is called before a pipe pluggable's onActivated on Forge/NeoForge.
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return bindOnBlock(stack, context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // Fabric does not call onItemUseFirst; vanilla item use is its fallback.
        return bindOnBlock(context.getItemInHand(), context);
    }

    private static InteractionResult bindOnBlock(ItemStack stack, UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        if (level.getBlockEntity(ctx.getClickedPos()) instanceof TileRobotProcessor panel) {
            return bindProcessor(stack, player, panel);
        }
        return linkStation(stack, player, ctx.getClickedPos(), ctx.getClickedFace());
    }

    public boolean doesSneakBypassUse(ItemStack stack, LevelReader world, BlockPos pos, Player player) {
        return true;
    }

    private static InteractionResult openBoundPanel(ItemStack stack, Player player, InteractionHand hand) {
        if (isClient(playerLevel(player))) return InteractionResult.SUCCESS;
        TileRobotProcessor panel = resolvePanel(stack, playerLevel(player));
        if (panel == null) {
            notice(player, "Linked panel is missing, unloaded or in another dimension");
            return InteractionResult.FAIL;
        }
        // Delegate to the existing per-loader menu open path (Forge, NeoForge, Fabric).
        BlockPos pos = panel.getBlockPos();
        return panel.onActivated(player, hand,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    //? if mc_26_x && (>=26.3) {
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        return openBoundPanel(player.getItemInHand(hand), player, hand);
    }
    //?} else {
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return new InteractionResultHolder<>(openBoundPanel(stack, player, hand), stack);
    }
    //?}
}
