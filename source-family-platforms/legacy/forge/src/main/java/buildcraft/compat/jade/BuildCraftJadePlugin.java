/*
 * Copyright (c) 2011-2018 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.compat.jade;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;
import buildcraft.lib.internal.mj.MjReceiverEnergyStorage;
import buildcraft.lib.internal.mj.MjCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.authlib.GameProfile;

import buildcraft.lib.internal.mj.IMjReadable;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.blockEntity.TileEngineCreative;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.TileDynamoMJ;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.tile.TileZonePlanner;
import buildcraft.silicon.tile.TileLaserTableBase;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.FluidView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ItemView;
import snownee.jade.api.view.ProgressView;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.api.ui.IElement;

@WailaPlugin
public final class BuildCraftJadePlugin implements snownee.jade.api.IWailaPlugin {
    private static final String MODID = "buildcraftlib";
    private static final String DATA_ROOT = "BuildCraft";

    private static final ResourceLocation CONFIG_BLOCK = id("block_details");
    private static final ResourceLocation CONFIG_OWNER = id("owner");
    private static final ResourceLocation CONFIG_PIPE = id("pipe_details");
    private static final ResourceLocation CONFIG_ROBOT = id("robot_details");

    private static final ResourceLocation UID_BLOCK = id("block");
    private static final ResourceLocation UID_ENTITY_ROBOT = id("robot");
    private static final ResourceLocation UID_ITEMS = id("items");
    private static final ResourceLocation UID_FLUIDS = id("fluids");
    private static final ResourceLocation UID_MJ = id("mj");
    private static final ResourceLocation UID_PROGRESS = id("progress");

    private static final int PROVIDER_PRIORITY = -500;

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, "jade_" + path);
    }

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BlockProvider.INSTANCE, TileBC_Neptune.class);
        registration.registerEntityDataProvider(RobotProvider.INSTANCE, EntityRobot.class);

        registration.registerItemStorage(ItemStorageProvider.INSTANCE, TileBC_Neptune.class);
        registration.registerItemStorage(ItemStorageProvider.INSTANCE, EntityRobotBase.class);

        registration.registerFluidStorage(FluidStorageProvider.INSTANCE, TileBC_Neptune.class);
        registration.registerFluidStorage(FluidStorageProvider.INSTANCE, EntityRobotBase.class);

        registration.registerEnergyStorage(MjStorageProvider.INSTANCE, TileBC_Neptune.class);
        registration.registerEnergyStorage(MjStorageProvider.INSTANCE, EntityRobotBase.class);

        registration.registerProgress(ProgressProvider.INSTANCE, TileZonePlanner.class);
        registration.registerProgress(ProgressProvider.INSTANCE, TileLaserTableBase.class);
        registration.registerProgress(ProgressProvider.INSTANCE, TilePipeHolder.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addConfig(CONFIG_BLOCK, true);
        registration.addConfig(CONFIG_OWNER, false);
        registration.addConfig(CONFIG_PIPE, true);
        registration.addConfig(CONFIG_ROBOT, true);

        registration.registerBlockComponent(BlockProvider.INSTANCE, BlockBCTile_Neptune.class);
        registration.registerEntityComponent(RobotProvider.INSTANCE, EntityRobot.class);

        registration.registerItemStorageClient(ItemStorageProvider.INSTANCE);
        registration.registerFluidStorageClient(FluidStorageProvider.INSTANCE);
        registration.registerEnergyStorageClient(MjStorageProvider.INSTANCE);
        registration.registerProgressClient(ProgressProvider.INSTANCE);

        registration.usePickedResult(BCCoreBlocks.ENGINE_BC8.get());
        for (BCRegistryEntry<LiquidBlock> block : BCEnergyFluids.OIL_BLOCK) {
            try {
                registration.usePickedResult(block.get());
            } catch (IllegalStateException ignored) {
                // The registry object is not ready in very early client setup paths.
            }
        }
        registration.addTooltipCollectedCallback(1000, BuildCraftJadePlugin::preserveBuildCraftTitleColours);
    }

    private enum BlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_BLOCK;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof TileBC_Neptune tile)) {
                return;
            }

            CompoundTag root = new CompoundTag();
            appendOwner(root, tile, accessor.showDetails());
            appendEngine(root, tile);
            appendPipe(root, tile, accessor.showDetails());
            appendLaserTable(root, tile);
            appendZonePlanner(root, tile);

            if (!root.isEmpty()) {
                data.put(DATA_ROOT, root);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!config.get(CONFIG_BLOCK)) {
                return;
            }
            CompoundTag root = accessor.getServerData().getCompound(DATA_ROOT);
            if (root.isEmpty()) {
                return;
            }

            if (config.get(CONFIG_OWNER) && root.contains("Owner")) {
                tooltip.add(line("owner", Component.literal(root.getString("Owner")).withStyle(ChatFormatting.WHITE)));
            }

            if (root.contains("Engine")) {
                appendEngineTooltip(tooltip, root.getCompound("Engine"));
            }
            if (config.get(CONFIG_PIPE) && root.contains("Pipe")) {
                appendPipeTooltip(tooltip, root.getCompound("Pipe"), accessor.showDetails());
            }
            if (root.contains("LaserTable")) {
                appendLaserTooltip(tooltip, root.getCompound("LaserTable"));
            }
            if (root.contains("ZonePlanner")) {
                appendZonePlannerTooltip(tooltip, root.getCompound("ZonePlanner"));
            }
        }
    }

    private enum RobotProvider implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_ENTITY_ROBOT;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (!(accessor.getEntity() instanceof EntityRobot robot)) {
                return;
            }

            CompoundTag root = new CompoundTag();
            CompoundTag robotTag = new CompoundTag();
            GameProfile owner = robot.getOwnerProfile();
            if (owner != null
                && !FakePlayerProvider.NULL_PROFILE.getId().equals(owner.getId())
                && owner.getName() != null && !owner.getName().isBlank()) {
                robotTag.putString("Owner", owner.getName());
            }
            if (robot.getBoardEntry() != null) {
                if (robot.getBoardEntry().id() != null) {
                    robotTag.putString("Board", robot.getBoardEntry().id());
                }
                if (robot.getBoardEntry().key() != null) {
                    robotTag.putString("BoardKey", robot.getBoardEntry().key());
                }
            }
            robotTag.putBoolean("Sleeping", robot.isAsleepForRendering());
            robotTag.putBoolean("Moving", robot.isMoving());
            robotTag.putBoolean("Docked", robot.getDockingStation() != null);
            robotTag.putBoolean("HasItems", robot.containsItems());
            robotTag.putBoolean("HasFreeSlot", robot.hasFreeSlot());
            robotTag.putInt("Energy", robot.getEnergy());
            robotTag.putInt("EnergyMax", EntityRobotBase.MAX_ENERGY);

            if (accessor.showDetails()) {
                DockingStation linked = robot.getLinkedStation();
                DockingStation docked = robot.getDockingStation();
                if (linked != null) {
                    robotTag.putString("LinkedStation", stationToString(linked));
                }
                if (docked != null) {
                    robotTag.putString("DockingStation", stationToString(docked));
                }
            }

            root.put("Robot", robotTag);
            data.put(DATA_ROOT, root);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!config.get(CONFIG_ROBOT)) {
                return;
            }
            CompoundTag root = accessor.getServerData().getCompound(DATA_ROOT);
            CompoundTag robotTag = root.getCompound("Robot");
            if (robotTag.isEmpty()) {
                return;
            }

            if (robotTag.contains("Owner")) {
                tooltip.add(line("robot.owner", Component.literal(robotTag.getString("Owner")).withStyle(ChatFormatting.WHITE)));
            }
            if (robotTag.contains("BoardKey")) {
                tooltip.add(line("robot.board", Component.translatable("buildcraft.boardRobot." + robotTag.getString("BoardKey")).withStyle(ChatFormatting.WHITE)));
            } else if (robotTag.contains("Board")) {
                tooltip.add(line("robot.board", Component.literal(robotTag.getString("Board")).withStyle(ChatFormatting.WHITE)));
            }
            tooltip.add(line("robot.state", robotState(robotTag)));
            if (accessor.showDetails()) {
                if (robotTag.contains("LinkedStation")) {
                    tooltip.add(line("robot.linked_station", Component.literal(robotTag.getString("LinkedStation")).withStyle(ChatFormatting.WHITE)));
                }
                if (robotTag.contains("DockingStation")) {
                    tooltip.add(line("robot.docking_station", Component.literal(robotTag.getString("DockingStation")).withStyle(ChatFormatting.WHITE)));
                }
            }
        }
    }

    private enum ItemStorageProvider implements IServerExtensionProvider<Object, ItemStack>, IClientExtensionProvider<ItemStack, ItemView> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_ITEMS;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public List<ViewGroup<ItemStack>> getGroups(ServerPlayer player, ServerLevel world, Object target, boolean showDetails) {
            int maxSize = showDetails ? 54 : 9;
            if (target instanceof EntityRobotBase robot) {
                List<ItemStack> stacks = new ArrayList<>();
                ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
                if (!held.isEmpty()) {
                    stacks.add(held.copy());
                }
                for (int i = 0; i < robot.getContainerSize(); i++) {
                    ItemStack stack = robot.getItem(i);
                    if (!stack.isEmpty()) {
                        stacks.add(stack.copy());
                    }
                }
                ViewGroup<ItemStack> group = ItemView.compacted(stacks.stream(), maxSize);
                group.id = "robot";
                return group.views.isEmpty() ? null : List.of(group);
            }

            if (target instanceof TileBC_Neptune tile) {
                IItemHandler handler = firstItemHandler(tile);
                if (handler == null || handler.getSlots() <= 0) {
                    return null;
                }
                ViewGroup<ItemStack> group = itemHandlerGroup(handler, maxSize);
                group.id = "inventory";
                return group.views.isEmpty() ? null : List.of(group);
            }
            return null;
        }

        @Override
        public List<ClientViewGroup<ItemView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<ItemStack>> groups) {
            return ClientViewGroup.map(groups, ItemView::new, BuildCraftJadePlugin::decorateGroupTitle);
        }
    }

    private enum FluidStorageProvider implements IServerExtensionProvider<Object, CompoundTag>, IClientExtensionProvider<CompoundTag, FluidView> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_FLUIDS;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel world, Object target, boolean showDetails) {
            if (BCLibConfig.hideFluidValues) {
                return null;
            }
            if (target instanceof EntityRobotBase robot) {
                List<ViewGroup<CompoundTag>> groups = fluidHandlerGroups(robot, "robot_tank");
                if (groups == null || groups.isEmpty()) {
                    return null;
                }
                groups.get(0).id = "robot_tank";
                return groups;
            }

            if (target instanceof TileBC_Neptune tile) {
                List<ViewGroup<CompoundTag>> groups = new ArrayList<>();
                for (Tank tank : tile.tankManager) {
                    int capacity = tank.getCapacity();
                    if (capacity <= 0) {
                        continue;
                    }
                    FluidStack fluid = tank.getFluid();
                    if (fluid == null) {
                        fluid = FluidStack.EMPTY;
                    }
                    ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(writeFluidView(fluid, capacity)));
                    String tankName = tank.getTankName();
                    group.id = tankName == null || tankName.isBlank() ? "tank" : tankName;
                    groups.add(group);
                }
                if (!groups.isEmpty()) {
                    return groups;
                }

                IFluidHandler handler = firstFluidHandler(tile);
                if (handler == null || handler.getTanks() <= 0) {
                    return null;
                }
                groups = fluidHandlerGroups(handler, "tank");
                if (groups == null || groups.isEmpty()) {
                    return null;
                }
                groups.get(0).id = "tank";
                return groups;
            }
            return null;
        }

        @Override
        public List<ClientViewGroup<FluidView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
            if (BCLibConfig.hideFluidValues) {
                return Collections.emptyList();
            }
            return ClientViewGroup.map(groups, FluidView::readDefault, BuildCraftJadePlugin::decorateGroupTitle);
        }
    }

    private enum MjStorageProvider implements IServerExtensionProvider<Object, CompoundTag>, IClientExtensionProvider<CompoundTag, EnergyView> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_MJ;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel world, Object target, boolean showDetails) {
            if (BCLibConfig.hidePowerValues) {
                return null;
            }
            if (target instanceof EntityRobotBase robot) {
                CompoundTag tag = robotEnergyTag(robot.getBattery().getStored(), robot.getBattery().getCapacity());
                ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
                group.id = "robot_energy";
                group.getExtraData().putString("Unit", "MJ");
                return List.of(group);
            }
            if (!(target instanceof TileBC_Neptune tile)) {
                return null;
            }
            // Pipe energy buffers are transport implementation details, not player-facing batteries.
            // Pipes expose current throughput / throughput capacity through ProgressProvider instead.
            if (tile instanceof TilePipeHolder) {
                return null;
            }

            List<ViewGroup<CompoundTag>> groups = new ArrayList<>();
            boolean hasDedicatedMjView = false;

            if (tile instanceof TileLaserTableBase table) {
                long targetPower = Math.max(0L, table.getTarget());
                if (targetPower > 0L) {
                    CompoundTag tag = mjEnergyTag(table.power, targetPower);
                    ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
                    group.id = "mj";
                    group.getExtraData().putString("Unit", "MJ");
                    groups.add(group);
                    hasDedicatedMjView = true;
                }
            } else if (tile instanceof TileDynamoMJ dynamo) {
                CompoundTag tag = mjEnergyTag(dynamo.getMjStored(), dynamo.getMjCapacity());
                ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
                group.id = "mj";
                group.getExtraData().putString("Unit", "MJ");
                groups.add(group);
                hasDedicatedMjView = true;
            } else if (tile instanceof TileEngineBase_BC8 engine) {
                CompoundTag tag = mjEnergyTag(engine.getEnergyStored(), engine.getMaxPower());
                ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
                group.id = "mj";
                group.getExtraData().putString("Unit", "MJ");
                groups.add(group);
                hasDedicatedMjView = true;
            }

            if (!hasDedicatedMjView) {
                groups.addAll(mjReadableGroups(tile));
            }
            groups.addAll(feEnergyGroups(tile));
            return groups.isEmpty() ? null : groups;
        }

        @Override
        public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
            if (BCLibConfig.hidePowerValues) {
                return Collections.emptyList();
            }
            return groups.stream().map(group -> {
                String unit = group.getExtraData().getString("Unit");
                ClientViewGroup<EnergyView> client = new ClientViewGroup<>(group.views.stream()
                        .map(tag -> readEnergyView(tag, unit.isBlank() ? "MJ" : unit))
                        .filter(view -> view != null)
                        .toList());
                decorateGroupTitle(group, client);
                return client;
            }).toList();
        }
    }

    private enum ProgressProvider implements IServerExtensionProvider<Object, CompoundTag>, IClientExtensionProvider<CompoundTag, ProgressView> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return UID_PROGRESS;
        }

        @Override
        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        @Override
        public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel world, Object target, boolean showDetails) {
            if (target instanceof TileZonePlanner planner) {
                if (planner.progress <= 0) {
                    return null;
                }
                return progressGroup("zone_planner", clamp01(planner.progress / (float) TileZonePlanner.CRAFT_TIME));
            }
            if (target instanceof TileLaserTableBase table) {
                long targetPower = table.getTarget();
                if (targetPower <= 0L) {
                    return null;
                }
                return progressGroup("laser", clamp01(table.power / (float) targetPower));
            }
            if (target instanceof TilePipeHolder holder) {
                return pipeThroughputGroups(holder);
            }
            return null;
        }

        @Override
        public List<ClientViewGroup<ProgressView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
            return ClientViewGroup.map(groups, ProgressView::read, (serverGroup, clientGroup) -> {
                decorateGroupTitle(serverGroup, clientGroup);
                for (ProgressView view : clientGroup.views) {
                    String flowType = serverGroup.getExtraData().getString("FlowType");
                    if (!flowType.isBlank()) {
                        view.text = pipeThroughputText(
                            flowType,
                            serverGroup.getExtraData().getLong("FlowCurrent")
                        );
                    } else if (serverGroup.id != null) {
                        view.text = Component.translatable("buildcraft.jade.progress." + safeTranslationPart(serverGroup.id));
                    }
                }
            });
        }
    }

    private static ViewGroup<ItemStack> itemHandlerGroup(IItemHandler handler, int maxSize) {
        List<ItemStack> stacks = new ArrayList<>();
        int limit = Math.min(handler.getSlots(), Math.max(1, maxSize) * 3);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return ItemView.compacted(stacks.stream(), maxSize);
    }

    private static List<ViewGroup<CompoundTag>> fluidHandlerGroups(IFluidHandler handler, String defaultId) {
        List<ViewGroup<CompoundTag>> groups = new ArrayList<>();
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            int capacity = handler.getTankCapacity(tank);
            if (capacity <= 0) {
                continue;
            }
            ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(writeFluidView(handler.getFluidInTank(tank), capacity)));
            group.id = defaultId;
            groups.add(group);
        }
        return groups.isEmpty() ? null : groups;
    }

    private static CompoundTag writeFluidView(FluidStack fluid, int capacity) {
        FluidStack safeFluid = fluid == null ? FluidStack.EMPTY : fluid;
        CompoundTag fluidTag = safeFluid.getTag();
        JadeFluidObject object = JadeFluidObject.of(
                safeFluid.getFluid(),
                Math.max(0, safeFluid.getAmount()),
                fluidTag == null ? null : fluidTag.copy()
        );
        return FluidView.writeDefault(object, Math.max(0, capacity));
    }

    private static List<ViewGroup<CompoundTag>> pipeThroughputGroups(TilePipeHolder holder) {
        Pipe pipe = holder.getPipe();
        if (pipe == Pipe.EMPTY || pipe.getFlow() == null) return null;

        if (pipe.getFlow() instanceof PipeFlowPower power) {
            if (BCLibConfig.hidePowerValues) return null;
            return List.of(pipeThroughputGroup(
                "pipe_mj_flow", "mj", power.getAverageThroughput(), power.getTransferCapacityPerTick()
            ));
        }
        if (pipe.getFlow() instanceof PipeFlowForgeEnergy energy) {
            if (BCLibConfig.hidePowerValues) return null;
            return List.of(pipeThroughputGroup(
                "pipe_fe_flow", "fe", energy.getAverageThroughput(), energy.getTransferCapacityPerTick()
            ));
        }
        if (pipe.getFlow() instanceof PipeFlowFluids fluids) {
            if (BCLibConfig.hideFluidValues) return null;
            return List.of(pipeThroughputGroup(
                "pipe_fluid_flow", "fluid", fluids.getAverageThroughput(), fluids.getTransferCapacityPerTick()
            ));
        }
        return null;
    }

    private static ViewGroup<CompoundTag> pipeThroughputGroup(String id, String flowType, long current, long capacity) {
        long safeCapacity = Math.max(0L, capacity);
        long safeCurrent = Math.max(0L, Math.min(safeCapacity, current));
        float ratio = safeCapacity <= 0L ? 0.0F : clamp01(safeCurrent / (float) safeCapacity);
        ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(ProgressView.create(ratio)));
        group.id = id;
        group.getExtraData().putString("FlowType", flowType);
        group.getExtraData().putLong("FlowCurrent", safeCurrent);
        group.getExtraData().putLong("FlowCapacity", safeCapacity);
        return group;
    }

    private static MutableComponent pipeThroughputText(String flowType, long current) {
        return switch (flowType) {
            case "mj" -> LocaleUtil.localizeMjFlow(current);
            case "fe" -> LocaleUtil.localizeFeFlow(current);
            case "fluid" -> LocaleUtil.localizeFluidFlow(current);
            default -> Component.literal(Long.toString(current));
        };
    }

    private static void appendOwner(CompoundTag root, TileBC_Neptune tile, boolean showDetails) {
        if (!showDetails) {
            return;
        }
        GameProfile owner = tile.getKnownOwner();
        if (owner != null && owner.isComplete() && owner.getName() != null && !owner.getName().isBlank()) {
            root.putString("Owner", owner.getName());
        }
    }

    private static void appendEngine(CompoundTag root, TileBC_Neptune tile) {
        if (!(tile instanceof TileEngineBase_BC8 engine)) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("NameKey", engineNameKey(engine));
        String stage = engine instanceof TileEngineCreative ? "blue" : engine.getPowerStage().name().toLowerCase(Locale.ROOT);
        tag.putString("Stage", stage);
        tag.putDouble("Heat", engine.getHeat());
        tag.putFloat("HeatLevel", (float) clamp01(engine.getHeatLevel()));
        tag.putLong("Output", engine.currentOutput);
        tag.putBoolean("OutputFe", engine instanceof TileDynamoMJ);
        tag.putBoolean("Redstone", engine.isRedstonePowered);
        tag.putBoolean("Burning", engine.isBurning());
        root.put("Engine", tag);
    }

    private static void appendPipe(CompoundTag root, TileBC_Neptune tile, boolean showDetails) {
        if (!(tile instanceof TilePipeHolder holder)) {
            return;
        }
        Pipe pipe = holder.getPipe();
        if (pipe == Pipe.EMPTY || pipe.definition == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", pipe.definition.identifier.toString());
        tag.putString("NameKey", pipe.definition.identifier.toLanguageKey("pipe"));
        DyeColor colour = pipe.getColour();
        if (colour != null) {
            tag.putString("Colour", colour.getName());
        }

        ListTag connections = new ListTag();
        ListTag plugs = new ListTag();
        for (Direction direction : Direction.values()) {
            if (pipe.isConnected(direction)) {
                ConnectedType type = pipe.getConnectedType(direction);
                connections.add(StringTag.valueOf(direction.getName() + ":" + (type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT))));
            }
            if (showDetails) {
                PipePluggable plug = holder.getPluggable(direction);
                if (plug != PipePluggable.EMPTY && plug.definition != null) {
                    String plugText = direction.getName() + ":" + plug.definition.identifier;
                    if (plug.isBlocking()) {
                        plugText += ":blocking";
                    }
                    plugs.add(StringTag.valueOf(plugText));
                }
            }
        }
        if (!connections.isEmpty()) {
            tag.put("Connections", connections);
        }
        if (!plugs.isEmpty()) {
            tag.put("Plugs", plugs);
        }

        if (showDetails) {
            ListTag wires = new ListTag();
            for (var entry : holder.wireManager.parts.entrySet()) {
                EnumWirePart part = entry.getKey();
                DyeColor wireColour = entry.getValue();
                String wire = part.name().toLowerCase(Locale.ROOT) + ":" + wireColour.getName();
                if (holder.wireManager.isPowered(part)) {
                    wire += ":powered";
                }
                wires.add(StringTag.valueOf(wire));
            }
            if (!wires.isEmpty()) {
                tag.put("Wires", wires);
            }
        }
        root.put("Pipe", tag);
    }

    private static void appendLaserTable(CompoundTag root, TileBC_Neptune tile) {
        if (!(tile instanceof TileLaserTableBase table)) {
            return;
        }
        long target = Math.max(0L, table.getTarget());
        if (target <= 0L) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong("Power", table.power);
        tag.putLong("Target", target);
        root.put("LaserTable", tag);
    }

    private static void appendZonePlanner(CompoundTag root, TileBC_Neptune tile) {
        if (!(tile instanceof TileZonePlanner planner)) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Layer", planner.getCurrentSelectedArea() + 1);
        if (planner.mapName != null && !planner.mapName.isBlank()) {
            tag.putString("MapName", planner.mapName);
        }
        if (planner.progress > 0) {
            tag.putFloat("Progress", clamp01(planner.progress / (float) TileZonePlanner.CRAFT_TIME));
        }
        root.put("ZonePlanner", tag);
    }

    private static void appendEngineTooltip(ITooltip tooltip, CompoundTag tag) {
        if (tag.contains("NameKey")) {
            tooltip.add(line("engine.name", Component.translatable(tag.getString("NameKey")).withStyle(ChatFormatting.WHITE)));
        }
        tooltip.add(line("engine.stage", Component.translatable("buildcraft.jade.engine.stage." + safeTranslationPart(tag.getString("Stage"))).withStyle(ChatFormatting.WHITE)));
        tooltip.add(line("engine.heat", Component.literal(String.format(Locale.ROOT, "%.1f C", tag.getDouble("Heat"))).withStyle(ChatFormatting.WHITE)));
        long output = tag.getLong("Output");
        if (output > 0L) {
            MutableComponent value = BCLibConfig.hidePowerValues
                ? Component.translatable("buildcraft.value.hidden")
                : tag.getBoolean("OutputFe") ? LocaleUtil.localizeFeFlow(output) : LocaleUtil.localizeMjFlow(output);
            tooltip.add(line("engine.output", value.withStyle(ChatFormatting.WHITE)));
        }
        tooltip.add(line("engine.redstone", bool(tag.getBoolean("Redstone"))));
    }

    private static void appendPipeTooltip(ITooltip tooltip, CompoundTag tag, boolean showDetails) {
        tooltip.add(line("pipe.id", Component.literal(tag.getString("Id")).withStyle(ChatFormatting.WHITE)));
        if (tag.contains("Colour")) {
            tooltip.add(line("pipe.colour", dyeColorComponent(tag.getString("Colour"))));
        }
        int connectionCount = tag.getList("Connections", Tag.TAG_STRING).size();
        tooltip.add(line("pipe.connections", Component.literal(Integer.toString(connectionCount)).withStyle(ChatFormatting.WHITE)));
        if (showDetails) {
            addStringList(tooltip, "pipe.connection", tag.getList("Connections", Tag.TAG_STRING));
            addStringList(tooltip, "pipe.pluggable", tag.getList("Plugs", Tag.TAG_STRING));
            addWireList(tooltip, tag.getList("Wires", Tag.TAG_STRING));
        }
    }

    private static void appendLaserTooltip(ITooltip tooltip, CompoundTag tag) {
        MutableComponent value = BCLibConfig.hidePowerValues
            ? Component.translatable("buildcraft.value.hidden")
            : Component.literal(MjFormatting.formatMicroMj(Math.max(0L, tag.getLong("Target") - tag.getLong("Power"))) + " MJ");
        tooltip.add(line("laser.required", value.withStyle(ChatFormatting.WHITE)));
    }

    private static void appendZonePlannerTooltip(ITooltip tooltip, CompoundTag tag) {
        tooltip.add(line("zone.layer", Component.literal(Integer.toString(tag.getInt("Layer"))).withStyle(ChatFormatting.WHITE)));
        if (tag.contains("MapName")) {
            tooltip.add(line("zone.name", Component.literal(tag.getString("MapName")).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static IItemHandler firstItemHandler(TileBC_Neptune tile) {
        for (Direction side : nullableDirections()) {
            LazyOptional<IItemHandler> optional = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side);
            IItemHandler handler = optional.orElse(null);
            if (handler != null && handler.getSlots() > 0) {
                return handler;
            }
        }
        return null;
    }

    private static IFluidHandler firstFluidHandler(TileBC_Neptune tile) {
        for (Direction side : nullableDirections()) {
            LazyOptional<IFluidHandler> optional = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, side);
            IFluidHandler handler = optional.orElse(null);
            if (handler != null && handler.getTanks() > 0) {
                return handler;
            }
        }
        return null;
    }

    private static List<ViewGroup<CompoundTag>> feEnergyGroups(TileBC_Neptune tile) {
        Set<IEnergyStorage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        long stored = 0L;
        long capacity = 0L;
        for (Direction side : nullableDirections()) {
            IEnergyStorage energy = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, side).orElse(null);
            if (energy == null || energy instanceof MjReceiverEnergyStorage || !seen.add(energy)) {
                continue;
            }
            int max = Math.max(0, energy.getMaxEnergyStored());
            if (max <= 0) {
                continue;
            }
            capacity += max;
            stored += Math.max(0, Math.min(max, energy.getEnergyStored()));
        }
        if (capacity <= 0L) {
            return Collections.emptyList();
        }
        CompoundTag tag = energyTag(stored, capacity, 1L);
        tag.putString("Unit", "FE");
        ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
        group.id = "fe";
        group.getExtraData().putString("Unit", "FE");
        return List.of(group);
    }

    private static List<ViewGroup<CompoundTag>> mjReadableGroups(TileBC_Neptune tile) {
        List<ViewGroup<CompoundTag>> groups = new ArrayList<>();
        Set<IMjReadable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction side : nullableDirections()) {
            IMjReadable readable = tile.getCapability(MjCapabilities.CAP_READABLE, side).orElse(null);
            if (readable == null || !seen.add(readable)) {
                continue;
            }
            if (readable.getCapacity() <= 0L) {
                continue;
            }
            CompoundTag tag = mjEnergyTag(readable.getStored(), readable.getCapacity());
            ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(tag));
            group.id = "mj";
            group.getExtraData().putString("Unit", "MJ");
            groups.add(group);
        }
        return groups;
    }

    private static Direction[] nullableDirections() {
        Direction[] values = Direction.values();
        Direction[] sides = new Direction[values.length + 1];
        sides[0] = null;
        System.arraycopy(values, 0, sides, 1, values.length);
        return sides;
    }

    private static CompoundTag robotEnergyTag(long currentRobotEnergy, long capacityRobotEnergy) {
        return mjEnergyTag(EntityRobot.robotEnergyToMicroMj(currentRobotEnergy), EntityRobot.robotEnergyToMicroMj(capacityRobotEnergy));
    }

    private static CompoundTag mjEnergyTag(long current, long capacity) {
        CompoundTag tag = energyTag(current, capacity, MjAmount.MICRO_MJ_PER_MJ);
        tag.putString("Unit", "MJ");
        tag.putLong("MicroCur", Math.max(0L, current));
        tag.putLong("MicroCapacity", Math.max(0L, capacity));
        return tag;
    }

    private static EnergyView readEnergyView(CompoundTag tag, String unit) {
        if (BCLibConfig.hidePowerValues) {
            return null;
        }
        String tagUnit = tag.getString("Unit");
        if (("MJ".equals(unit) || "MJ".equals(tagUnit)) && tag.contains("MicroCapacity", Tag.TAG_LONG)) {
            long capacity = tag.getLong("MicroCapacity");
            if (capacity <= 0L) {
                return null;
            }
            long current = Math.max(0L, Math.min(capacity, tag.getLong("MicroCur")));
            EnergyView view = new EnergyView();
            view.current = MjFormatting.formatMicroMj(current) + " MJ";
            view.max = MjFormatting.formatMicroMj(capacity) + " MJ";
            view.ratio = (float) (current / (double) capacity);
            view.overrideText = Component.literal(view.current + " / " + view.max).withStyle(ChatFormatting.WHITE);
            return view;
        }
        return EnergyView.read(tag, unit);
    }

    private static CompoundTag energyTag(long current, long capacity, long divisor) {
        int max = toDisplayEnergy(capacity, divisor);
        int cur = Math.min(max, toDisplayEnergy(current, divisor));
        CompoundTag tag = new CompoundTag();
        tag.putInt("Capacity", max);
        tag.putInt("Cur", Math.max(0, cur));
        return tag;
    }

    private static int toDisplayEnergy(long value, long divisor) {
        if (value <= 0L) {
            return 0;
        }
        long safeDivisor = Math.max(1L, divisor);
        long scaled = (value + safeDivisor - 1L) / safeDivisor;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    private static List<ViewGroup<CompoundTag>> progressGroup(String id, float progress) {
        ViewGroup<CompoundTag> group = new ViewGroup<>(List.of(ProgressView.create(progress)));
        group.id = id;
        return List.of(group);
    }

    private static void decorateGroupTitle(ViewGroup<?> serverGroup, ClientViewGroup<?> clientGroup) {
        // A non-null ClientViewGroup title makes Jade wrap the group in a themed BoxElement.
        // BuildCraft already labels its values, so that wrapper only adds an unexpected coloured
        // background behind BCCE data. Keep native item/fluid/energy/progress views inline.
        clientGroup.title = null;
    }

    private static Component robotState(CompoundTag robotTag) {
        String state;
        if (robotTag.getBoolean("Sleeping")) {
            state = "sleeping";
        } else if (robotTag.getBoolean("Docked")) {
            state = "docked";
        } else if (robotTag.getBoolean("Moving")) {
            state = "moving";
        } else {
            state = "idle";
        }
        return Component.translatable("buildcraft.jade.robot.state." + state).withStyle(ChatFormatting.WHITE);
    }

    private static Component bool(boolean value) {
        return Component.translatable(value ? "buildcraft.jade.yes" : "buildcraft.jade.no").withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component line(String key, Component value) {
        return Component.translatable("buildcraft.jade." + key, value).withStyle(ChatFormatting.GRAY);
    }

    private static void addWireList(ITooltip tooltip, ListTag list) {
        for (Tag entry : list) {
            String[] parts = entry.getAsString().split(":");
            if (parts.length >= 2) {
                MutableComponent value = Component.empty()
                        .append(Component.literal(parts[0]).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                        .append(dyeColorComponent(parts[1]));
                if (parts.length >= 3 && "powered".equals(parts[2])) {
                    value.append(Component.literal(" ").append(Component.translatable("buildcraft.jade.pipe.wire.powered").withStyle(ChatFormatting.GREEN)));
                }
                tooltip.add(line("pipe.wire", value));
            } else {
                tooltip.add(line("pipe.wire", Component.literal(entry.getAsString()).withStyle(ChatFormatting.WHITE)));
            }
        }
    }

    private static Component dyeColorComponent(String name) {
        DyeColor colour = DyeColor.byName(name, null);
        if (colour == null) {
            return Component.literal(name).withStyle(ChatFormatting.WHITE);
        }
        return Component.translatable("color.minecraft." + colour.getName()).withStyle(style -> style.withColor(colour.getTextColor()));
    }

    private static void addStringList(ITooltip tooltip, String key, ListTag list) {
        for (Tag entry : list) {
            tooltip.add(line(key, Component.literal(entry.getAsString()).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static void preserveBuildCraftTitleColours(ITooltip tooltip, Accessor<?> accessor) {
        if (tooltip.isEmpty()) {
            return;
        }
        Component title = getBuildCraftTitle(accessor);
        if (title == null) {
            return;
        }
        List<IElement> left = tooltip.get(0, IElement.Align.LEFT);
        if (left == null || left.isEmpty()) {
            return;
        }
        left.clear();
        left.add(tooltip.getElementHelper().text(title));
    }

    private static Component getBuildCraftTitle(Accessor<?> accessor) {
        if (accessor instanceof BlockAccessor blockAccessor) {
            BlockState state = blockAccessor.getBlockState();
            Block block = state.getBlock();
            if (blockAccessor.getBlockEntity() instanceof TileEngineBase_BC8 engine) {
                return Component.translatable(engineNameKey(engine)).withStyle(ChatFormatting.WHITE);
            }
            if (blockAccessor.getBlockEntity() instanceof TilePipeHolder holder) {
                CompoundTag root = blockAccessor.getServerData().getCompound(DATA_ROOT);
                CompoundTag pipeData = root.getCompound("Pipe");
                String nameKey = pipeData.getString("NameKey");
                if (!nameKey.isEmpty()) {
                    return Component.translatable(nameKey).withStyle(ChatFormatting.WHITE);
                }
                Pipe pipe = holder.getPipe();
                if (pipe != null && pipe != Pipe.EMPTY) {
                    var pipeItem = PipeRegistry.INSTANCE.getItemForPipe(pipe.getDefinition());
                    if (pipeItem instanceof Item item) {
                        return new ItemStack(item).getHoverName();
                    }
                }
            }
            if (state.hasProperty(BuildCraftProperties.ENGINE_TYPE)) {
                return Component.translatable(engineNameKey(state)).withStyle(ChatFormatting.WHITE);
            }
            if (block instanceof LiquidBlock && isBuildCraftBlock(block)) {
                return block.getName();
            }
            ItemStack picked = blockAccessor.getPickedResult();
            if (isBuildCraftStack(picked)) {
                return picked.getHoverName();
            }
            if (isBuildCraftBlock(block)) {
                return block.getName();
            }
            return null;
        }
        if (accessor instanceof EntityAccessor entityAccessor && entityAccessor.getEntity() instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            if (isBuildCraftStack(stack)) {
                return stack.getHoverName();
            }
        }
        return null;
    }

    private static boolean isBuildCraftBlock(Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key != null && key.getNamespace().startsWith("buildcraft");
    }

    private static boolean isBuildCraftStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key != null && key.getNamespace().startsWith("buildcraft");
    }

    private static String engineNameKey(TileEngineBase_BC8 engine) {
        if (engine instanceof TileDynamoMJ) {
            return "block.buildcraftenergy.mj_dynamo";
        }
        return engineNameKey(engine.getBlockState());
    }

    private static String engineNameKey(BlockState state) {
        if (state.hasProperty(BuildCraftProperties.ENGINE_TYPE)) {
            return "block.buildcraftcore.engine_" + state.getValue(BuildCraftProperties.ENGINE_TYPE).getSerializedName();
        }
        return "block.buildcraftcore.engine";
    }

    private static String stationToString(DockingStation station) {
        Direction side = station.side();
        return station.x() + ", " + station.y() + ", " + station.z() + (side == null ? "" : " / " + side.getName());
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value <= 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, value);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, value);
    }

    private static String safeTranslationPart(String id) {
        return id.toLowerCase(Locale.ROOT).replace(':', '.').replace('/', '.').replace('-', '_');
    }
}
