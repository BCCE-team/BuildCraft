//? source if >=1.21.11
/*
 * Copyright (c) 2011-2018 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.compat.jade;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import javax.annotation.Nullable;

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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.core.registries.BuiltInRegistries;
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
import snownee.jade.api.ui.BoxElement;
import snownee.jade.api.JadeIds;
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.misc.CapUtil;

@WailaPlugin
public final class BuildCraftJadePlugin implements snownee.jade.api.IWailaPlugin {
    private static final String MODID = "buildcraftlib";
    private static final String DATA_ROOT = "BuildCraft";

    private static final Identifier CONFIG_BLOCK = id("block_details");
    private static final Identifier CONFIG_OWNER = id("owner");
    private static final Identifier CONFIG_PIPE = id("pipe_details");
    private static final Identifier CONFIG_ROBOT = id("robot_details");

    private static final Identifier UID_BLOCK = id("block");
    private static final Identifier UID_ENTITY_ROBOT = id("robot");
    private static final Identifier UID_ITEMS = id("items");
    private static final Identifier UID_FLUIDS = id("fluids");
    private static final Identifier UID_MJ = id("mj");
    private static final Identifier UID_PROGRESS = id("progress");

    private static final int PROVIDER_PRIORITY = -500;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, "jade_" + path);
    }

    public void register(IWailaCommonRegistration registration) {
        registration.blockOperations().pick(ResourceKey.create(Registries.BLOCK,
                BuiltInRegistries.BLOCK.getKey(BCCoreBlocks.ENGINE_BC8.get())));
        for (BCRegistryEntry<LiquidBlock> block : BCEnergyFluids.OIL_BLOCK) {
            if (block.isBound()) {
                registration.blockOperations().pick(ResourceKey.create(Registries.BLOCK,
                        BuiltInRegistries.BLOCK.getKey(block.get())));
            }
        }

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

        registration.addTooltipCollectedCallback(1000, BuildCraftJadePlugin::preserveBuildCraftTitleColours);
    }

    private enum BlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        public Identifier getUid() {
            return UID_BLOCK;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

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

        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!config.get(CONFIG_BLOCK)) {
                return;
            }
            CompoundTag root = accessor.getServerData().getCompoundOrEmpty(DATA_ROOT);
            if (root.isEmpty()) {
                return;
            }

            if (config.get(CONFIG_OWNER) && root.contains("Owner")) {
                tooltip.add(line("owner", Component.literal(NbtCompat.getString(root, "Owner")).withStyle(ChatFormatting.WHITE)));
            }

            if (root.contains("Engine")) {
                appendEngineTooltip(tooltip, NbtCompat.getCompound(root, "Engine"));
            }
            if (config.get(CONFIG_PIPE) && root.contains("Pipe")) {
                appendPipeTooltip(tooltip, NbtCompat.getCompound(root, "Pipe"), accessor.showDetails());
            }
            if (root.contains("LaserTable")) {
                appendLaserTooltip(tooltip, NbtCompat.getCompound(root, "LaserTable"));
            }
            if (root.contains("ZonePlanner")) {
                appendZonePlannerTooltip(tooltip, NbtCompat.getCompound(root, "ZonePlanner"));
            }
        }
    }

    private enum RobotProvider implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
        INSTANCE;

        public Identifier getUid() {
            return UID_ENTITY_ROBOT;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (!(accessor.getEntity() instanceof EntityRobot robot)) {
                return;
            }

            CompoundTag root = new CompoundTag();
            CompoundTag robotTag = new CompoundTag();
            GameProfile owner = robot.getOwnerProfile();
            if (owner != null
                && !GameProfileCompat.id(FakePlayerProvider.NULL_PROFILE).equals(GameProfileCompat.id(owner))
                && GameProfileCompat.name(owner) != null && !GameProfileCompat.name(owner).isBlank()) {
                robotTag.putString("Owner", GameProfileCompat.name(owner));
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

        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!config.get(CONFIG_ROBOT)) {
                return;
            }
            CompoundTag root = accessor.getServerData().getCompoundOrEmpty(DATA_ROOT);
            CompoundTag robotTag = NbtCompat.getCompound(root, "Robot");
            if (robotTag.isEmpty()) {
                return;
            }

            if (robotTag.contains("Owner")) {
                tooltip.add(line("robot.owner", Component.literal(NbtCompat.getString(robotTag, "Owner")).withStyle(ChatFormatting.WHITE)));
            }
            if (robotTag.contains("BoardKey")) {
                tooltip.add(line("robot.board", Component.translatable("buildcraft.boardRobot." + NbtCompat.getString(robotTag, "BoardKey")).withStyle(ChatFormatting.WHITE)));
            } else if (robotTag.contains("Board")) {
                tooltip.add(line("robot.board", Component.literal(NbtCompat.getString(robotTag, "Board")).withStyle(ChatFormatting.WHITE)));
            }
            tooltip.add(line("robot.state", robotState(robotTag)));
            if (accessor.showDetails()) {
                if (robotTag.contains("LinkedStation")) {
                    tooltip.add(line("robot.linked_station", Component.literal(NbtCompat.getString(robotTag, "LinkedStation")).withStyle(ChatFormatting.WHITE)));
                }
                if (robotTag.contains("DockingStation")) {
                    tooltip.add(line("robot.docking_station", Component.literal(NbtCompat.getString(robotTag, "DockingStation")).withStyle(ChatFormatting.WHITE)));
                }
            }
        }
    }

    private enum ItemStorageProvider implements IServerExtensionProvider<ItemStack>, IClientExtensionProvider<ItemStack, ItemView> {
        INSTANCE;

        public Identifier getUid() {
            return UID_ITEMS;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        public List<ViewGroup<ItemStack>> getGroups(Accessor<?> accessor) {
            Object target = accessor.getTarget();
            boolean showDetails = accessor.showDetails();
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
                ViewGroup<ItemStack> group = compactedItemGroup(stacks, maxSize);
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

        public List<ClientViewGroup<ItemView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<ItemStack>> groups) {
            return ClientViewGroup.map(groups, ItemView::new, BuildCraftJadePlugin::decorateGroupTitle);
        }
    }

    private enum FluidStorageProvider implements IServerExtensionProvider<FluidView.Data>, IClientExtensionProvider<FluidView.Data, FluidView> {
        INSTANCE;

        public Identifier getUid() {
            return UID_FLUIDS;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        public List<ViewGroup<FluidView.Data>> getGroups(Accessor<?> accessor) {
            if (BCLibConfig.hideFluidValues) {
                return Collections.emptyList();
            }
            Object target = accessor.getTarget();
            if (target instanceof EntityRobotBase robot) {
                List<ViewGroup<FluidView.Data>> groups = fluidHandlerGroups(robot, "robot_tank");
                if (groups == null || groups.isEmpty()) {
                    return null;
                }
                groups.get(0).id = "robot_tank";
                return groups;
            }

            if (target instanceof TileBC_Neptune tile) {
                List<ViewGroup<FluidView.Data>> groups = new ArrayList<>();
                for (Tank tank : tile.tankManager) {
                    int capacity = tank.getCapacity();
                    if (capacity <= 0) {
                        continue;
                    }
                    FluidStack fluid = tank.getFluid();
                    if (fluid == null) {
                        fluid = FluidStack.EMPTY;
                    }
                    FluidView.Data fluidView = writeFluidView(fluid, capacity);
                    if (fluidView == null) {
                        return Collections.emptyList();
                    }
                    ViewGroup<FluidView.Data> group = new ViewGroup<>(List.of(fluidView));
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

        public List<ClientViewGroup<FluidView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<FluidView.Data>> groups) {
            if (BCLibConfig.hideFluidValues || groups == null || groups.isEmpty()) {
                return Collections.emptyList();
            }
            return ClientViewGroup.map(groups, FluidView::readDefault, BuildCraftJadePlugin::decorateGroupTitle);
        }
    }

    private enum MjStorageProvider implements IServerExtensionProvider<EnergyView.Data>, IClientExtensionProvider<EnergyView.Data, EnergyView> {
        INSTANCE;

        public Identifier getUid() {
            return UID_MJ;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        public List<ViewGroup<EnergyView.Data>> getGroups(Accessor<?> accessor) {
            if (BCLibConfig.hidePowerValues) {
                return Collections.emptyList();
            }
            Object target = accessor.getTarget();
            if (target instanceof EntityRobotBase robot) {
                EnergyView.Data tag = robotEnergyTag(robot.getBattery().getStored(), robot.getBattery().getCapacity());
                ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
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
                return Collections.emptyList();
            }

            List<ViewGroup<EnergyView.Data>> groups = new ArrayList<>();
            boolean hasDedicatedMjView = false;

            if (tile instanceof TileLaserTableBase table) {
                long targetPower = Math.max(0L, table.getTarget());
                if (targetPower > 0L) {
                    EnergyView.Data tag = mjEnergyTag(table.power, targetPower);
                    ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
                    group.id = "mj";
                    group.getExtraData().putString("Unit", "MJ");
                    groups.add(group);
                    hasDedicatedMjView = true;
                }
            } else if (tile instanceof TileDynamoMJ dynamo) {
                EnergyView.Data tag = mjEnergyTag(dynamo.getMjStored(), dynamo.getMjCapacity());
                ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
                group.id = "mj";
                group.getExtraData().putString("Unit", "MJ");
                groups.add(group);
                hasDedicatedMjView = true;
            } else if (tile instanceof TileEngineBase_BC8 engine) {
                EnergyView.Data tag = mjEnergyTag(engine.getEnergyStored(), engine.getMaxPower());
                ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
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

        public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<EnergyView.Data>> groups) {
            if (BCLibConfig.hidePowerValues) {
                return Collections.emptyList();
            }
            return groups.stream().map(group -> {
                String unit = group.getExtraData().getStringOr("Unit", "MJ");
                ClientViewGroup<EnergyView> client = new ClientViewGroup<>(group.views.stream()
                        .map(tag -> readEnergyView(tag, unit.isBlank() ? "MJ" : unit))
                        .filter(view -> view != null)
                        .toList());
                decorateGroupTitle(group, client);
                return client;
            }).toList();
        }
    }

    private enum ProgressProvider implements IServerExtensionProvider<ProgressView.Data>, IClientExtensionProvider<ProgressView.Data, ProgressView> {
        INSTANCE;

        public Identifier getUid() {
            return UID_PROGRESS;
        }

        public int getDefaultPriority() {
            return PROVIDER_PRIORITY;
        }

        public List<ViewGroup<ProgressView.Data>> getGroups(Accessor<?> accessor) {
            Object target = accessor.getTarget();
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

        public List<ClientViewGroup<ProgressView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<ProgressView.Data>> groups) {
            return ClientViewGroup.map(groups, ProgressView::read, (serverGroup, clientGroup) -> {
                decorateGroupTitle(serverGroup, clientGroup);
                for (ProgressView view : clientGroup.views) {
                    String flowType = serverGroup.getExtraData().getStringOr("FlowType", "");
                    if (!flowType.isBlank()) {
                        view.text = pipeThroughputText(
                            flowType,
                            serverGroup.getExtraData().getLongOr("FlowCurrent", 0L)
                        );
                    } else if (serverGroup.id != null) {
                        view.text = Component.translatable("buildcraft.jade.progress." + safeTranslationPart(serverGroup.id));
                    }
                }
            });
        }
    }

    private static ViewGroup<ItemStack> compactedItemGroup(List<ItemStack> stacks, int maxSize) {
        int limit = Math.max(1, maxSize);
        List<ItemStack> compacted = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack matching = null;
            for (ItemStack existing : compacted) {
                if (ItemStack.isSameItemSameComponents(existing, stack)) {
                    matching = existing;
                    break;
                }
            }
            if (matching != null) {
                long combined = (long) matching.getCount() + stack.getCount();
                matching.setCount((int) Math.min(Integer.MAX_VALUE, combined));
            } else if (compacted.size() < limit) {
                compacted.add(stack.copy());
            }
        }
        return new ViewGroup<>(compacted);
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
        return compactedItemGroup(stacks, maxSize);
    }

    private static List<ViewGroup<FluidView.Data>> fluidHandlerGroups(IFluidHandler handler, String defaultId) {
        List<ViewGroup<FluidView.Data>> groups = new ArrayList<>();
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            int capacity = handler.getTankCapacity(tank);
            if (capacity <= 0) {
                continue;
            }
            FluidView.Data fluidView = writeFluidView(handler.getFluidInTank(tank), capacity);
            if (fluidView == null) {
                return Collections.emptyList();
            }
            ViewGroup<FluidView.Data> group = new ViewGroup<>(List.of(fluidView));
            group.id = defaultId;
            groups.add(group);
        }
        return groups.isEmpty() ? null : groups;
    }

    private static FluidView.Data writeFluidView(FluidStack fluid, int capacity) {
        return JadeViewData.fluid(fluid, capacity);
    }

    private static List<ViewGroup<ProgressView.Data>> pipeThroughputGroups(TilePipeHolder holder) {
        Pipe pipe = holder.getPipe();
        if (pipe == Pipe.EMPTY || pipe.getFlow() == null) return null;

        if (pipe.getFlow() instanceof PipeFlowPower power) {
            if (BCLibConfig.hidePowerValues) return Collections.emptyList();
            return List.of(pipeThroughputGroup(
                "pipe_mj_flow", "mj", power.getAverageThroughput(), power.getTransferCapacityPerTick()
            ));
        }
        if (pipe.getFlow() instanceof PipeFlowForgeEnergy energy) {
            if (BCLibConfig.hidePowerValues) return Collections.emptyList();
            return List.of(pipeThroughputGroup(
                "pipe_fe_flow", "fe", energy.getAverageThroughput(), energy.getTransferCapacityPerTick()
            ));
        }
        if (pipe.getFlow() instanceof PipeFlowFluids fluids) {
            if (BCLibConfig.hideFluidValues) return Collections.emptyList();
            return List.of(pipeThroughputGroup(
                "pipe_fluid_flow", "fluid", fluids.getAverageThroughput(), fluids.getTransferCapacityPerTick()
            ));
        }
        return null;
    }

    private static ViewGroup<ProgressView.Data> pipeThroughputGroup(String id, String flowType, long current, long capacity) {
        long safeCapacity = Math.max(0L, capacity);
        long safeCurrent = Math.max(0L, Math.min(safeCapacity, current));
        float ratio = safeCapacity <= 0L ? 0.0F : clamp01(safeCurrent / (float) safeCapacity);
        ViewGroup<ProgressView.Data> group = new ViewGroup<>(List.of(new ProgressView.Data(ratio)));
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
        if (owner != null && GameProfileCompat.name(owner) != null && !GameProfileCompat.name(owner).isBlank()) {
            root.putString("Owner", GameProfileCompat.name(owner));
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
            tooltip.add(line("engine.name", Component.translatable(NbtCompat.getString(tag, "NameKey")).withStyle(ChatFormatting.WHITE)));
        }
        tooltip.add(line("engine.stage", Component.translatable("buildcraft.jade.engine.stage." + safeTranslationPart(NbtCompat.getString(tag, "Stage"))).withStyle(ChatFormatting.WHITE)));
        tooltip.add(line("engine.heat", Component.literal(String.format(Locale.ROOT, "%.1f C", NbtCompat.getDouble(tag, "Heat"))).withStyle(ChatFormatting.WHITE)));
        long output = NbtCompat.getLong(tag, "Output");
        if (output > 0L) {
            MutableComponent value = BCLibConfig.hidePowerValues
                ? Component.translatable("buildcraft.value.hidden")
                : NbtCompat.getBoolean(tag, "OutputFe") ? LocaleUtil.localizeFeFlow(output) : LocaleUtil.localizeMjFlow(output);
            tooltip.add(line("engine.output", value.withStyle(ChatFormatting.WHITE)));
        }
        tooltip.add(line("engine.redstone", bool(NbtCompat.getBoolean(tag, "Redstone"))));
    }

    private static void appendPipeTooltip(ITooltip tooltip, CompoundTag tag, boolean showDetails) {
        tooltip.add(line("pipe.id", Component.literal(NbtCompat.getString(tag, "Id")).withStyle(ChatFormatting.WHITE)));
        if (tag.contains("Colour")) {
            tooltip.add(line("pipe.colour", dyeColorComponent(NbtCompat.getString(tag, "Colour"))));
        }
        int connectionCount = NbtCompat.getList(tag, "Connections").size();
        tooltip.add(line("pipe.connections", Component.literal(Integer.toString(connectionCount)).withStyle(ChatFormatting.WHITE)));
        if (showDetails) {
            addStringList(tooltip, "pipe.connection", NbtCompat.getList(tag, "Connections"));
            addStringList(tooltip, "pipe.pluggable", NbtCompat.getList(tag, "Plugs"));
            addWireList(tooltip, NbtCompat.getList(tag, "Wires"));
        }
    }

    private static void appendLaserTooltip(ITooltip tooltip, CompoundTag tag) {
        MutableComponent value = BCLibConfig.hidePowerValues
            ? Component.translatable("buildcraft.value.hidden")
            : Component.literal(MjFormatting.formatMicroMj(Math.max(0L, NbtCompat.getLong(tag, "Target") - NbtCompat.getLong(tag, "Power"))) + " MJ");
        tooltip.add(line("laser.required", value.withStyle(ChatFormatting.WHITE)));
    }

    private static void appendZonePlannerTooltip(ITooltip tooltip, CompoundTag tag) {
        tooltip.add(line("zone.layer", Component.literal(Integer.toString(NbtCompat.getInt(tag, "Layer"))).withStyle(ChatFormatting.WHITE)));
        if (tag.contains("MapName")) {
            tooltip.add(line("zone.name", Component.literal(NbtCompat.getString(tag, "MapName")).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static IItemHandler firstItemHandler(TileBC_Neptune tile) {
        for (Direction side : nullableDirections()) {
            IItemHandler handler = tile.getCapability(CapUtil.CAP_ITEMS, side);
            if (handler != null && handler.getSlots() > 0) {
                return handler;
            }
        }
        return null;
    }

    private static IFluidHandler firstFluidHandler(TileBC_Neptune tile) {
        for (Direction side : nullableDirections()) {
            IFluidHandler handler = tile.getCapability(CapUtil.CAP_FLUIDS, side);
            if (handler != null && handler.getTanks() > 0) {
                return handler;
            }
        }
        return null;
    }

    private static List<ViewGroup<EnergyView.Data>> feEnergyGroups(TileBC_Neptune tile) {
        Set<IEnergyStorage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        long stored = 0L;
        long capacity = 0L;
        for (Direction side : nullableDirections()) {
            IEnergyStorage energy = tile.getCapability(CapUtil.CAP_FE, side);
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
        EnergyView.Data tag = JadeViewData.energy(stored, capacity);
        ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
        group.id = "fe";
        group.getExtraData().putString("Unit", "FE");
        return List.of(group);
    }

    private static List<ViewGroup<EnergyView.Data>> mjReadableGroups(TileBC_Neptune tile) {
        List<ViewGroup<EnergyView.Data>> groups = new ArrayList<>();
        Set<IMjReadable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction side : nullableDirections()) {
            IMjReadable readable = tile.getCapability(MjCapabilities.CAP_READABLE, side);
            if (readable == null || !seen.add(readable)) {
                continue;
            }
            if (readable.getCapacity() <= 0L) {
                continue;
            }
            EnergyView.Data tag = mjEnergyTag(readable.getStored(), readable.getCapacity());
            ViewGroup<EnergyView.Data> group = new ViewGroup<>(List.of(tag));
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

    private static EnergyView.Data robotEnergyTag(long currentRobotEnergy, long capacityRobotEnergy) {
        return mjEnergyTag(EntityRobot.robotEnergyToMicroMj(currentRobotEnergy),
                EntityRobot.robotEnergyToMicroMj(capacityRobotEnergy));
    }

    private static EnergyView.Data mjEnergyTag(long current, long capacity) {
        // Micro-MJ stays long all the way to the client; no integer MJ rounding or overflow.
        return JadeViewData.energy(current, capacity);
    }

    private static EnergyView readEnergyView(EnergyView.Data data, String unit) {
        return BCLibConfig.hidePowerValues ? null : JadeViewData.readEnergy(data, unit);
    }

    private static List<ViewGroup<ProgressView.Data>> progressGroup(String id, float progress) {
        ViewGroup<ProgressView.Data> group = new ViewGroup<>(List.of(new ProgressView.Data(clamp01(progress))));
        group.id = id;
        return List.of(group);
    }

    private static void decorateGroupTitle(ViewGroup<?> serverGroup, ClientViewGroup<?> clientGroup) {
        if (serverGroup.id == null || serverGroup.id.isBlank()) {
            return;
        }
        String id = safeTranslationPart(serverGroup.id);
        switch (id) {
            case "robot", "inventory", "tank", "robot_tank", "robot_energy", "mj", "fe", "zone_planner", "laser", "pipe_mj_flow", "pipe_fe_flow", "pipe_fluid_flow" ->
                    clientGroup.title = Component.translatable("buildcraft.jade.group." + id);
            default -> clientGroup.title = Component.translatable("buildcraft.jade.group.generic", Component.literal(serverGroup.id));
        }
    }

    private static Component robotState(CompoundTag robotTag) {
        String state;
        if (NbtCompat.getBoolean(robotTag, "Sleeping")) {
            state = "sleeping";
        } else if (NbtCompat.getBoolean(robotTag, "Docked")) {
            state = "docked";
        } else if (NbtCompat.getBoolean(robotTag, "Moving")) {
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
            String[] parts = NbtCompat.getString(entry).split(":");
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
                tooltip.add(line("pipe.wire", Component.literal(NbtCompat.getString(entry)).withStyle(ChatFormatting.WHITE)));
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
            tooltip.add(line(key, Component.literal(NbtCompat.getString(entry)).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static void preserveBuildCraftTitleColours(BoxElement rootElement, Accessor<?> accessor) {
        Component title = getBuildCraftTitle(accessor);
        if (title != null) {
            // Do not clear the first row: Jade can place its compact item icon beside the title.
            rootElement.getTooltip().replace(JadeIds.CORE_OBJECT_NAME, title);
        }
    }

    private static Component getBuildCraftTitle(Accessor<?> accessor) {
        if (accessor instanceof BlockAccessor blockAccessor) {
            BlockState state = blockAccessor.getBlockState();
            Block block = state.getBlock();
            if (blockAccessor.getBlockEntity() instanceof TileEngineBase_BC8 engine) {
                return Component.translatable(engineNameKey(engine)).withStyle(ChatFormatting.WHITE);
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
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null && key.getNamespace().startsWith("buildcraft");
    }

    private static boolean isBuildCraftStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
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
