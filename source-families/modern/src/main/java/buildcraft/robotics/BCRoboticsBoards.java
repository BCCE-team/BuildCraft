package buildcraft.robotics;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;

import buildcraft.lib.misc.ItemStackUtil;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import buildcraft.robotics.internal.legacy.boards.IRedstoneBoard;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardNBT;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRegistry;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.robotics.boards.BoardRobotCarrier;
import buildcraft.robotics.boards.BoardRobotDelivery;
import buildcraft.robotics.boards.BoardRobotFluidCarrier;
import buildcraft.robotics.boards.BoardRobotHarvester;
import buildcraft.robotics.boards.BoardRobotBomber;
import buildcraft.robotics.boards.BoardRobotBuilder;
import buildcraft.robotics.boards.BoardRobotButcher;
import buildcraft.robotics.boards.BoardRobotFarmer;
import buildcraft.robotics.boards.BoardRobotLeaveCutter;
import buildcraft.robotics.boards.BoardRobotKnight;
import buildcraft.robotics.boards.BoardRobotLumberjack;
import buildcraft.robotics.boards.BoardRobotMiner;
import buildcraft.robotics.boards.BoardRobotPicker;
import buildcraft.robotics.boards.BoardRobotPlanter;
import buildcraft.robotics.boards.BoardRobotPump;
import buildcraft.robotics.boards.BoardRobotShovelman;
import buildcraft.robotics.boards.BoardRobotStripes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class BCRoboticsBoards {
    public static final int MODEL_EMPTY = 0;

    private static final Map<String, BoardEntry> ENTRIES_BY_ID = new LinkedHashMap<>();
    private static final Map<String, BoardEntry> ENTRIES_BY_KEY = new LinkedHashMap<>();
    private static boolean initialized;

    /** BuildCraft 7.1.27 laser recipes used legacy energy units. BCCE preserves their balance at 10 legacy units = 1 MJ. */
    private static final int LEGACY_ENERGY_UNITS_PER_MJ = 10;

    public static final BoardEntry EMPTY = board("buildcraft:boardRobotEmpty", "empty", "clean", "robot_base", 0);

    static {
        // Order copied from BuildCraft 7.1.x BuildCraftRobotics.preInit(). This order is also used by the creative tab.
        board("buildcraft:boardRobotPicker", "picker", "green", "robot_picker", legacyEnergyToMj(8_000));
        board("buildcraft:boardRobotCarrier", "carrier", "green", "robot_carrier", legacyEnergyToMj(8_000));
        board("buildcraft:boardRobotFluidCarrier", "fluid_carrier", "green", "robot_fluid_carrier", legacyEnergyToMj(8_000));

        board("buildcraft:boardRobotLumberjack", "lumberjack", "blue", "robot_lumberjack", legacyEnergyToMj(32_000));
        board("buildcraft:boardRobotHarvester", "harvester", "blue", "robot_harvester", legacyEnergyToMj(32_000));
        board("buildcraft:miner", "miner", "blue", "robot_miner", legacyEnergyToMj(32_000));
        board("buildcraft:boardRobotPlanter", "planter", "blue", "robot_planter", legacyEnergyToMj(32_000));
        board("buildcraft:boardRobotFarmer", "farmer", "blue", "robot_farmer", legacyEnergyToMj(32_000));
        board("buildcraft:leave_cutter", "leave_cutter", "blue", "robot_leave_cutter", legacyEnergyToMj(32_000));
        board("buildcraft:boardRobotButcher", "butcher", "blue", "robot_butcher", legacyEnergyToMj(32_000));
        board("buildcraft:shovelman", "shovelman", "blue", "robot_shovelman", legacyEnergyToMj(32_000));
        board("buildcraft:boardRobotPump", "pump", "blue", "robot_pump", legacyEnergyToMj(32_000));

        board("buildcraft:boardRobotDelivery", "delivery", "green", "robot_delivery", legacyEnergyToMj(128_000));
        board("buildcraft:boardRobotKnight", "knight", "red", "robot_knight", legacyEnergyToMj(128_000));
        board("buildcraft:boardRobotBomber", "bomber", "red", "robot_bomber", legacyEnergyToMj(128_000));
        board("buildcraft:boardRobotStripes", "stripes", "yellow", "robot_stripes", legacyEnergyToMj(128_000));

        board("buildcraft:boardRobotBuilder", "builder", "yellow", "robot_builder", legacyEnergyToMj(512_000));
    }

    private BCRoboticsBoards() {
    }

    private static int legacyEnergyToMj(int legacyEnergyUnits) {
        return Math.round(legacyEnergyUnits / (float) LEGACY_ENERGY_UNITS_PER_MJ);
    }

    private static BoardEntry board(String legacyId, String key, String boardColor, String robotTexture, int energyCost) {
        int modelIndex = ENTRIES_BY_ID.size();
        BoardEntry entry = new BoardEntry(legacyId, key, boardColor, robotTexture, energyCost, modelIndex, new BasicBoardNBT(legacyId, key, boardColor, robotTexture));
        ENTRIES_BY_ID.put(legacyId, entry);
        ENTRIES_BY_KEY.put(key, entry);
        return entry;
    }

    /**
     * Board programming costs are stored as whole BuildCraft MJ after converting the original 7.1.27 balance
     * at 10 legacy energy units = 1 MJ. Convert to micro-MJ only when feeding modern energy APIs or formatting the tooltip.
     */
    public static String formatBoardEnergyCost(int energyCostMj) {
        if (BCLibConfig.hidePowerValues) {
            return LocaleUtil.localize("buildcraft.value.hidden");
        }
        if (energyCostMj <= 0) {
            return MjFormatting.formatMicroMj(0);
        }
        return MjFormatting.formatMicroMj((long) energyCostMj * MjAmount.MICRO_MJ_PER_MJ);
    }

    public static void init() {
        if (initialized && RedstoneBoardRegistry.instance != null) {
            return;
        }
        SimpleRedstoneBoardRegistry registry = new SimpleRedstoneBoardRegistry();
        registry.setEmptyRobotBoard(EMPTY.nbt());
        for (BoardEntry entry : ENTRIES_BY_ID.values()) {
            if (entry != EMPTY) {
                registry.registerBoardType(entry.nbt(), entry.energyCost());
            }
        }
        RedstoneBoardRegistry.instance = registry;
        initialized = true;
    }

    public static Collection<BoardEntry> entriesWithEmpty() {
        return Collections.unmodifiableCollection(ENTRIES_BY_ID.values());
    }

    public static Collection<BoardEntry> robotEntries() {
        return ENTRIES_BY_ID.values().stream().filter(entry -> entry != EMPTY).toList();
    }

    public static BoardEntry getById(String id) {
        BoardEntry entry = ENTRIES_BY_ID.get(id);
        return entry == null ? EMPTY : entry;
    }

    public static BoardEntry getByKey(String key) {
        BoardEntry entry = ENTRIES_BY_KEY.get(key);
        return entry == null ? EMPTY : entry;
    }

    public static BoardEntry getBoard(ItemStack stack) {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        if (tag.isEmpty()) {
            return EMPTY;
        }
        if (tag.contains("id")) {
            return getById(tag.getString("id"));
        }
        if (tag.contains("board")) {
            return getById(tag.getCompound("board").getString("id"));
        }
        return EMPTY;
    }

    public static BoardEntry getRobotBoard(ItemStack stack) {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        if (!tag.contains("board")) {
            return EMPTY;
        }
        return getById(tag.getCompound("board").getString("id"));
    }

    public static float getRobotModelValue(ItemStack stack) {
        return getRobotBoard(stack).modelValue();
    }

    public static float getBoardModelValue(ItemStack stack) {
        return getBoard(stack).boardColorValue();
    }

    public record BoardEntry(String id, String key, String boardColor, String robotTexture, int energyCost, int modelIndex,
                             BasicBoardNBT nbt) {
        public Component displayName() {
            return Component.translatable("buildcraft.boardRobot." + key);
        }

        public Component legacyDisplayName() {
            return Component.translatable("buildcraft." + legacyLangKey());
        }

        public String legacyLangKey() {
            return switch (key) {
                case "empty" -> "boardRobotClean";
                case "fluid_carrier" -> "boardRobotFluidCarrier";
                case "leave_cutter" -> "boardRobotLeaveCutter";
                default -> "boardRobot" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            };
        }

        public float modelValue() {
            return modelIndex / 100.0F;
        }

        public float boardColorValue() {
            return switch (boardColor) {
                case "green" -> 0.10F;
                case "blue" -> 0.20F;
                case "red" -> 0.30F;
                case "yellow" -> 0.40F;
                default -> 0.0F;
            };
        }

        public ResourceLocation robotTextureLocation() {
            return ResourceLocation.fromNamespaceAndPath(BCRobotics.MODID, "textures/entities/" + robotTexture + ".png");
        }

        public boolean isInDev() {
            return false;
        }
    }

    public static class BasicBoardNBT extends RedstoneBoardRobotNBT {
        private final String id;
        private final String key;
        private final String boardColor;
        private final ResourceLocation robotTexture;

        BasicBoardNBT(String id, String key, String boardColor, String robotTexture) {
            this.id = id;
            this.key = key;
            this.boardColor = boardColor;
            this.robotTexture = ResourceLocation.fromNamespaceAndPath(BCRobotics.MODID, "textures/entities/" + robotTexture + ".png");
        }

        @Override
        public String getID() {
            return id;
        }

        public String getKey() {
            return key;
        }

        public String getBoardColor() {
            return boardColor;
        }

        @Override
        public void addInformation(ItemStack stack, Player player, List<Component> list, boolean advanced) {
            BoardEntry entry = getById(id);
            if (entry.energyCost() > 0) {
                list.add(Component.translatable("tooltip.buildcraftrobotics.board.energy", formatBoardEnergyCost(entry.energyCost())));
            }
        }

        @Override
        public RedstoneBoardRobot create(EntityRobotBase robot) {
            if ("picker".equals(key)) {
                return new BoardRobotPicker(robot);
            }
            if ("carrier".equals(key)) {
                return new BoardRobotCarrier(robot);
            }
            if ("fluid_carrier".equals(key)) {
                return new BoardRobotFluidCarrier(robot);
            }
            if ("lumberjack".equals(key)) {
                return new BoardRobotLumberjack(robot);
            }
            if ("harvester".equals(key)) {
                return new BoardRobotHarvester(robot);
            }
            if ("miner".equals(key)) {
                return new BoardRobotMiner(robot);
            }
            if ("planter".equals(key)) {
                return new BoardRobotPlanter(robot);
            }
            if ("farmer".equals(key)) {
                return new BoardRobotFarmer(robot);
            }
            if ("leave_cutter".equals(key)) {
                return new BoardRobotLeaveCutter(robot);
            }
            if ("butcher".equals(key)) {
                return new BoardRobotButcher(robot);
            }
            if ("shovelman".equals(key)) {
                return new BoardRobotShovelman(robot);
            }
            if ("pump".equals(key)) {
                return new BoardRobotPump(robot);
            }
            if ("delivery".equals(key)) {
                return new BoardRobotDelivery(robot);
            }
            if ("knight".equals(key)) {
                return new BoardRobotKnight(robot);
            }
            if ("bomber".equals(key)) {
                return new BoardRobotBomber(robot);
            }
            if ("stripes".equals(key)) {
                return new BoardRobotStripes(robot);
            }
            if ("builder".equals(key)) {
                return new BoardRobotBuilder(robot);
            }
            return new BasicRobotBoard(robot, this);
        }

        @Override
        public ResourceLocation getRobotTexture() {
            return robotTexture;
        }
    }

    private static class BasicRobotBoard extends RedstoneBoardRobot {
        private final BasicBoardNBT nbt;

        BasicRobotBoard(EntityRobotBase robot, BasicBoardNBT nbt) {
            super(robot);
            this.nbt = nbt;
        }

        @Override
        public RedstoneBoardRobotNBT getNBTHandler() {
            return nbt;
        }

        @Override
        public boolean canLoadFromNBT() {
            // This no-op board is reconstructed from boardId and has no runtime state of its own. It is intentionally
            // not registered in RobotManager, so serializing it as an AIRobot would produce a null aiName.
            return false;
        }
    }

    private static class SimpleRedstoneBoardRegistry extends RedstoneBoardRegistry {
        private final Map<String, RedstoneBoardNBT<?>> boards = new LinkedHashMap<>();
        private final Map<String, Integer> energyCosts = new LinkedHashMap<>();
        private RedstoneBoardRobotNBT emptyRobotBoard;

        @Override
        public void registerBoardType(RedstoneBoardNBT<?> redstoneBoardNBT, int energyCost) {
            boards.put(redstoneBoardNBT.getID(), redstoneBoardNBT);
            energyCosts.put(redstoneBoardNBT.getID(), energyCost);
        }

        @Override
        public void registerBoardClass(RedstoneBoardNBT<?> redstoneBoardNBT, float probability) {
            registerBoardType(redstoneBoardNBT, legacyEnergyToMj(Math.round(160000 / probability)));
        }

        @Override
        public void setEmptyRobotBoard(RedstoneBoardRobotNBT redstoneBoardNBT) {
            emptyRobotBoard = redstoneBoardNBT;
        }

        @Override
        public RedstoneBoardRobotNBT getEmptyRobotBoard() {
            return emptyRobotBoard;
        }

        @Override
        public RedstoneBoardNBT<?> getRedstoneBoard(CompoundTag nbt) {
            return getRedstoneBoard(nbt.getString("id"));
        }

        @Override
        public RedstoneBoardNBT<?> getRedstoneBoard(String id) {
            return boards.getOrDefault(id, emptyRobotBoard);
        }

        @Override
        public void registerSprites(Consumer<ResourceLocation> spriteRegistrar) {
            for (BoardEntry entry : ENTRIES_BY_ID.values()) {
                spriteRegistrar.accept(ResourceLocation.fromNamespaceAndPath(BCRobotics.MODID, "items/board/" + entry.boardColor()));
            }
        }

        @Override
        public Collection<RedstoneBoardNBT<?>> getAllBoardNBTs() {
            return boards.values();
        }

        @Override
        public int getEnergyCost(RedstoneBoardNBT<?> board) {
            return board == null ? 0 : energyCosts.getOrDefault(board.getID(), 0);
        }
    }
}
