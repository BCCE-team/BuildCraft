/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.plug;

import net.fabricmc.loader.api.FabricLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;


import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.BCLib;
import buildcraft.api.v2.facade.FacadePhase;
import buildcraft.api.v2.facade.FacadeMaterial;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.api.v2.facade.FacadeMaterialAdapter;
import buildcraft.api.v2.facade.FacadeStateResult;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.ItemStackKey;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionResult;
import buildcraft.lib.misc.InteractionResultHolder;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;
import buildcraft.lib.compat.NbtCompat;

public enum FacadeStateManager implements FacadeMaterialAdapter {
    INSTANCE;


    public static final boolean DEBUG = BCDebugging.shouldDebugLog("silicon.facade");
    public static final SortedMap<BlockState, FacadeBlockStateInfo> validFacadeStates;
    public static final Map<ItemStackKey, List<FacadeBlockStateInfo>> stackFacades;
    public static volatile FacadeBlockStateInfo defaultState, previewState;
    private static volatile long indexGeneration;

    public static long indexGeneration() { return indexGeneration; }
    private static volatile boolean initialized;
    /** Guarded by FacadeStateManager.class. Validation reads phased states while this map is being built. */
    private static boolean initializing;

    /** Mods with confirmed facade-state incompatibilities that fail {@link #doesPropertyConform(Property)} or an
     * equivalent safety check. Entries apply only while the incompatible state representation is present. */
    private static final List<String> KNOWN_INVALID_REPORTED_MODS = Arrays.asList(new String[] { //
    });

    static {
        validFacadeStates = new TreeMap<>(BlockUtil.blockStateComparator());
        stackFacades = new HashMap<>();
        defaultState = new FacadeBlockStateInfo(Blocks.AIR.defaultBlockState(), StackUtil.EMPTY, ImmutableSet.of());
        previewState = defaultState;
    }

    public static FacadeBlockStateInfo getInfoForBlock(Block block) {
        return getInfoForState(block.defaultBlockState());
    }

    private static synchronized FacadeBlockStateInfo getInfoForState(BlockState state) {
        return validFacadeStates.get(state);
    }

    // Legacy NeoForge IMC registration is replaced by API v2 FacadeRules on Fabric.

    /** Block-wide exclusions. Every surviving state must still pass the geometry validator. */
    private static InteractionResultHolder<String> isValidFacadeBlock(Block block) {
        var disablingRule = BuildCraftApiRuntime.INSTANCE.facadeRules().disabledBy(block);
        if (disablingRule.isPresent()) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it has been disabled by " + disablingRule.get().owner());
        }
        if (isUserBlacklistedFacadeBlock(block)) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it is blacklisted for facade deduplication");
        }
        if (block instanceof LiquidBlock) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it is a fluid block");
        }
        String problem = FacadeStateValidator.blockProblem(block);
        if (problem != null) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, problem);
        }
        return new InteractionResultHolder<>(InteractionResult.PASS, "");
    }

    /** @return Any of:
     *         <ul>
     *         <li>{@link InteractionResult#SUCCESS} if this state is valid for a facade.
     *         <li>{@link InteractionResult#FAIL} with string describing the problem with this state (if it is not valid
     *         for a facade)</li>
     *         </ul>
     */
    private static InteractionResultHolder<String> isValidFacadeState(BlockState state) {
        if (state.isAir()) return new InteractionResultHolder<>(InteractionResult.FAIL, "it is air");
        if (!state.getFluidState().isEmpty()) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it contains a fluid");
        }
        if (state.hasBlockEntity()) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it has a tile entity");
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, "it doesn't have a normal model");
        }
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String problem = FacadeStateValidator.geometryProblem(state, id == null ? "" : id.getPath());
        if (problem != null) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, problem);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, "");
    }

    @Nonnull
    private static ItemStack getRequiredStack(BlockState state) {
        ItemStack stack = BuildCraftApiRuntime.INSTANCE.facadeRules().mappedStack(state).orElse(null);
        if (stack != null) {
            return stack;
        }
        Block block = state.getBlock();
        Item item = block.asItem();
        if (item != Items.AIR) {
            return new ItemStack(item, 1);
        }
        ItemStack clone = getCloneStack(block, state);
        if (!clone.isEmpty()) {
            clone.setCount(1);
            return clone;
        }
        return StackUtil.EMPTY;
    }

    /**
     * Invokes the public clone-stack extension overload available on the active Minecraft API. The signature changed
     * between supported NeoForge versions, but both variants dispatch to state-sensitive block overrides.
     */
    private static ItemStack getCloneStack(Block block, BlockState state) {
        try {
            Method current = block.getClass().getMethod("getCloneItemStack", LevelReader.class, BlockPos.class,
                BlockState.class, boolean.class, Player.class);
            Object result = current.invoke(block, null, BlockPos.ZERO, state, false, null);
            if (result instanceof ItemStack stack) return stack;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method legacy = block.getClass().getMethod("getCloneItemStack", LevelReader.class, BlockPos.class,
                BlockState.class);
            Object result = legacy.invoke(block, null, BlockPos.ZERO, state);
            if (result instanceof ItemStack stack) return stack;
        } catch (ReflectiveOperationException ignored) {
        }
        return StackUtil.EMPTY;
    }

    public static void init() {
        if (initialized) {
            return;
        }
        synchronized (FacadeStateManager.class) {
            // A third-party facade rule may re-enter this scan. Never rebuild the
            // partially populated maps recursively; other threads wait for the
            // class monitor and observe the completed scan.
            if (initialized || initializing) {
                return;
            }
            initializing = true;
            try {
                validFacadeStates.clear();
                stackFacades.clear();
                previewState = defaultState;

                indexGeneration++;
                Runnable commitCache = indexFacades();

                // A scan can be triggered while an early Minecraft/Fabric
                // registry is not yet fully populated.  Never permanently
                // cache that incomplete result: it would hide all facades and
                // make every subsequent NBT lookup resolve to AIR.
                if (!hasExpectedSentinel(Blocks.STONE) || !hasExpectedSentinel(Blocks.GLASS)) {
                    BCLog.logger.warn("[silicon.facade] Facade material index incomplete ({} states; stone/glass missing). Will retry at the next lifecycle event.",
                        validFacadeStates.size());
                    validFacadeStates.clear();
                    stackFacades.clear();
                    previewState = defaultState;
                    return;
                }
                previewState = validFacadeStates.getOrDefault(Blocks.BRICKS.defaultBlockState(),
                validFacadeStates.getOrDefault(Blocks.STONE.defaultBlockState(), defaultState));
                initialized = true;
                commitCache.run();
                BCLog.explicitEvent("[silicon.facade] Indexed {} facade states", validFacadeStates.size());
            } finally {
                initializing = false;
            }
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static synchronized FacadeBlockStateInfo getDefaultState() {
        if (defaultState == null) {
            defaultState = new FacadeBlockStateInfo(Blocks.AIR.defaultBlockState(), StackUtil.EMPTY, ImmutableSet.of());
        }
        return defaultState;
    }

    public static synchronized FacadeBlockStateInfo getPreviewState() {
        return previewState == null ? getDefaultState() : previewState;
    }

    /** Snapshot only the canonical discovery entries; do not expose a partially rebuilt map. */
    public static synchronized List<FacadeBlockStateInfo> getIndexedStates() {
        return List.copyOf(validFacadeStates.values());
    }

    /** The discovery allowlist is not the persisted material format. Never replace a decoded block with AIR. */
    public static synchronized FacadeBlockStateInfo resolvePersistedState(BlockState state) {
        if (state == null || state.isAir()) return getDefaultState();
        FacadeBlockStateInfo registered = validFacadeStates.get(state);
        if (registered != null) return registered;
        // An old representative, or a decode before the index is ready. Preserve the exact
        // material, without publishing it as a new craftable/creative variant.
        Item item = state.getBlock().asItem();
        ItemStack stack = item == Items.AIR ? StackUtil.EMPTY : new ItemStack(item, 1);
        return new FacadeBlockStateInfo(state, stack, ImmutableSet.copyOf(state.getProperties()));
    }

    /** Existing installed facades may load, but newly placed materials must satisfy current policy. */
    public static synchronized boolean canPlaceState(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (validFacadeStates.containsKey(state)) return true;
        try {
            if (isValidFacadeBlock(state.getBlock()).getResult() != InteractionResult.PASS
                || isValidFacadeState(state).getResult() != InteractionResult.SUCCESS
                || shouldSkipFacadeState(state.getBlock(), state)) return false;
            ItemStack required = getRequiredStack(state);
            if (required.isEmpty()) return false;
            List<FacadeBlockStateInfo> representatives = stackFacades.get(new ItemStackKey(required));
            if (representatives == null) return false;
            for (FacadeBlockStateInfo info : representatives) {
                if (info.state.getBlock() == state.getBlock()
                    && BlockUtil.getPropertiesStringMap(state, info.varyingProperties)
                        .equals(BlockUtil.getPropertiesStringMap(info.state, info.varyingProperties))) return true;
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void scanBlock(Block block, FacadePersistentCache.Capture capture) {
        try {
            if (!DEBUG && KNOWN_INVALID_REPORTED_MODS.contains(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
                if (BCLib.VERSION.startsWith("7.99")) {
                    BCLog.logger.warn(
                        "[silicon.facade] Skipping " + block + " as it has been added to the list of broken mods!");
                    return;
                }
            }

            InteractionResultHolder<String> result = isValidFacadeBlock(block);
            if (result.getResult() != InteractionResult.PASS) {
                if (DEBUG) {
                    BCLog.logger.info("[silicon.facade] Disallowed block " + BuiltInRegistries.BLOCK.getKey(block)
                        + " because " + result.getObject());
                }
                return;
            }

            // Check to make sure that all the properties work properly
            // Fixes a bug in extra utilities who doesn't serialise and deserialise properties properly

            boolean allPropertiesOk = true;
            for (Property<?> property : block.getStateDefinition().getProperties()) {
                allPropertiesOk &= doesPropertyConform(property);
            }
            if (!allPropertiesOk) {
                capture.failed = true;
                return;
            }

            BlockState defaultBlockState = block.defaultBlockState();
            java.util.Comparator<BlockState> stateOrder = BlockUtil.blockStateComparator();
            Map<BlockState, ItemStack> usedStates = new TreeMap<>((a, b) -> {
                if (a == b) return 0;
                if (a == defaultBlockState) return -1;
                if (b == defaultBlockState) return 1;
                return stateOrder.compare(a, b);
            });
            Map<ItemStackKey, Map<Property<?>, Comparable<?>>> varyingProperties = new HashMap<>();
            int skippedStateCount = 0;
            BlockState firstSkippedState = null;
            RuntimeException firstStateFailure = null;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                final ItemStack requiredStack;
                try {
                    // Even explicitly supported glass must satisfy state-level safety and geometry.
                    InteractionResultHolder<String> stateResult = isValidFacadeState(state);
                    if (stateResult.getResult() != InteractionResult.SUCCESS) {
                        if (DEBUG) {
                            BCLog.logger.info("[silicon.facade] Disallowed state " + state
                                + " because " + stateResult.getObject());
                        }
                        continue;
                    }
                    if (shouldSkipFacadeState(block, state)) {
                        continue;
                    }
                    requiredStack = getRequiredStack(state);
                } catch (RuntimeException e) {
                    skippedStateCount++;
                    if (firstStateFailure == null) {
                        firstStateFailure = e;
                        firstSkippedState = state;
                    }
                    continue;
                }
                usedStates.put(state, requiredStack);
                ItemStackKey stackKey = new ItemStackKey(requiredStack);
                Map<Property<?>, Comparable<?>> vars = varyingProperties.get(stackKey);
                List<Property.Value<?>> stateValues = state.getValues().toList();
                if (vars == null) {
                    vars = new HashMap<>();
                    for (Property.Value<?> value : stateValues) {
                        vars.put(value.property(), value.value());
                    }
                    varyingProperties.put(stackKey, vars);
                } else {
                    for (Property.Value<?> propertyValue : stateValues) {
                        Property<?> prop = propertyValue.property();
                        Comparable<?> value = propertyValue.value();
                        if (!Objects.equals(vars.get(prop), value)) {
                            vars.put(prop, null);
                        }
                    }
                }
            }
            FriendlyByteBuf testingBuffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                varyingProperties.forEach((key, vars) -> {
                    if (DEBUG) {
                        BCLog.logger.info("[silicon.facade]   pre-" + key + ":");
                        vars.keySet().forEach(p -> BCLog.logger.info("[silicon.facade]       " + p));
                    }
                    vars.entrySet().removeIf(entry -> Objects.nonNull(entry.getValue())
                        || shouldIgnoreFacadeProperty(block, entry.getKey()));
                    if (DEBUG && !vars.isEmpty()) {
                        BCLog.logger.info("[silicon.facade]   " + key + ":");
                        vars.keySet().forEach(p -> BCLog.logger.info("[silicon.facade]       " + p));
                    }
                });
                Map<ItemStackKey, Set<Map<String, String>>> addedSignatures = new HashMap<>();
                for (Entry<BlockState, ItemStack> entry : usedStates.entrySet()) {
                    BlockState state = entry.getKey();
                    ItemStack stack = entry.getValue();
                    ItemStackKey stackKey = new ItemStackKey(stack);
                    Map<Property<?>, Comparable<?>> vars = varyingProperties.get(stackKey);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Map<String, String> signature = BlockUtil.getPropertiesStringMap(state, vars.keySet());
                    Set<Map<String, String>> signatures = addedSignatures.computeIfAbsent(stackKey, k -> new HashSet<>());
                    if (signatures.contains(signature)) {
                        continue;
                    }
                    FacadeBlockStateInfo previousInfo = null;
                    boolean registeredForValidation = false;
                    try {
                        ImmutableSet<Property<?>> varSet = ImmutableSet.copyOf(vars.keySet());
                        FacadeBlockStateInfo info = new FacadeBlockStateInfo(state, stack, varSet);
                        // The NBT/buffer readers resolve the state through this map, so register it temporarily for
                        // validation and roll it back if a third-party state cannot be serialized safely.
                        previousInfo = validFacadeStates.put(state, info);
                        registeredForValidation = true;

                        FacadeStateRoundTrip.verify(info, testingBuffer);
                        if (!info.requiredStack.isEmpty()) {
                            stackFacades.computeIfAbsent(stackKey, k -> new ArrayList<>()).add(info);
                        }
                        if (DEBUG) {
                            BCLog.logger.info("[silicon.facade]   Added " + info);
                        }
                        capture.add(info);
                        signatures.add(signature);
                    } catch (RuntimeException e) {
                        testingBuffer.clear();
                        if (registeredForValidation) {
                            if (previousInfo == null) {
                                validFacadeStates.remove(state);
                            } else {
                                validFacadeStates.put(state, previousInfo);
                            }
                        }
                        skippedStateCount++;
                        if (firstStateFailure == null) {
                            firstStateFailure = e;
                            firstSkippedState = state;
                        }
                    }
                }
            } finally {
                testingBuffer.release();
            }
            if (skippedStateCount > 0) {
                capture.failed = true;
                Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
                String summary = "[silicon.facade] Skipped " + skippedStateCount + " invalid facade state(s) for "
                    + blockId + "; first failed state: " + firstSkippedState;
                if (DEBUG) {
                    BCLog.logger.warn(summary, firstStateFailure);
                } else {
                    BCLog.logger.warn(summary);
                }
            }
        } catch (RuntimeException e) {
            capture.failed = true;
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
            String summary = "[silicon.facade] Skipping " + blockId + " after "
                + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            if (DEBUG) {
                BCLog.logger.warn(summary, e);
            } else {
                BCLog.logger.warn(summary);
            }
        }
    }

    private static boolean hasExpectedSentinel(Block block) {
        // If an addon deliberately disables/maps stone or glass, the absence of its default
        // state is not an incomplete scan. The registry item must still exist.
        return block.asItem() != Items.AIR && (validFacadeStates.containsKey(block.defaultBlockState())
            || BuildCraftApiRuntime.INSTANCE.facadeRules().hasRuleFor(block));
    }

    private static Runnable indexFacades() {
        Map<String, String> versions = new TreeMap<>();
        try {
            FabricLoader.getInstance().getAllMods().forEach(mod ->
            versions.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        } catch (RuntimeException | LinkageError e) {
            BCLog.logger.warn("[silicon.facade] Mod version metadata unavailable: {}", e.toString());
            versions.clear();
        }
        return FacadePersistentCache.index(FabricLoader.getInstance().getGameDir(), versions, BuiltInRegistries.BLOCK,
            block -> Objects.toString(BuiltInRegistries.BLOCK.getKey(block), null),
            item -> Objects.toString(BuiltInRegistries.ITEM.getKey(item), null),
            FacadeStateManager::scanBlock, FacadeStateManager::registerCached,
            state -> isValidFacadeBlock(state.getBlock()).getResult() == InteractionResult.PASS
                && isValidFacadeState(state).getResult() == InteractionResult.SUCCESS
                && !shouldSkipFacadeState(state.getBlock(), state), DEBUG);
    }

    private static void registerCached(List<FacadeBlockStateInfo> infos) {
        if (infos.isEmpty()) return;
        Map<BlockState, FacadeBlockStateInfo> previous = new HashMap<>();
        List<FacadeBlockStateInfo> inserted = new ArrayList<>();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (FacadeBlockStateInfo info : infos) {
                previous.put(info.state, validFacadeStates.put(info.state, info));
                inserted.add(info);
                FacadeStateRoundTrip.verify(info, buffer);
            }
            for (FacadeBlockStateInfo info : inserted) {
                ItemStackKey key = new ItemStackKey(info.requiredStack);
                stackFacades.computeIfAbsent(key, k -> new ArrayList<>()).add(info);
            }
        } catch (RuntimeException | LinkageError e) {
            for (int i = inserted.size() - 1; i >= 0; i--) {
                FacadeBlockStateInfo info = inserted.get(i);
                FacadeBlockStateInfo old = previous.get(info.state);
                if (old == null) validFacadeStates.remove(info.state);
                else validFacadeStates.put(info.state, old);
                ItemStackKey key = new ItemStackKey(info.requiredStack);
                List<FacadeBlockStateInfo> list = stackFacades.get(key);
                if (list != null) {
                    list.remove(info);
                    if (list.isEmpty()) stackFacades.remove(key);
                }
            }
            throw e;
        } finally {
            buffer.release();
        }
    }

    private static boolean isUserBlacklistedFacadeBlock(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        if (path.startsWith("waxed_")) {
            return true;
        }
        if (path.contains("chute") || path.contains("vine") || path.contains("sculk_vein") || path.contains("glow_lichen")
            || path.contains("chorus_flower") || path.contains("sunflower") || path.contains("rose_bush")
            || path.contains("lilac") || path.contains("peony") || path.equals("cobweb")
            || path.equals("wheat") || path.equals("kelp") || path.equals("kelp_plant")) {
            return true;
        }
        if (block instanceof net.minecraft.world.level.block.SlabBlock) {
            return true;
        }
        BlockState state = block.defaultBlockState();
        if (state.is(BlockTags.FLOWERS)) {
            return true;
        }
        if (block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN) {
            return true;
        }
        return path.contains("berry");
    }

    private static boolean shouldSkipFacadeState(Block block, BlockState state) {
        if (block == Blocks.TARGET) {
            for (Property<?> property : state.getProperties()) {
                if ("power".equals(property.getName())) {
                    Object value = state.getValue((Property) property);
                    if (value instanceof Integer integer && integer.intValue() != 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean shouldIgnoreFacadeProperty(Block block, Property<?> property) {
        String name = property.getName();
        if ("waterlogged".equals(name) || "persistent".equals(name) || "distance".equals(name)
            || "facing".equals(name) || "horizontal_facing".equals(name) || "powered".equals(name)
            || "note".equals(name) || "instrument".equals(name) || "type".equals(name)) {
            return true;
        }
        return false;
    }

    private static <V extends Comparable<V>> boolean doesPropertyConform(Property<V> property) {
        try {
            property.getValue("");
        } catch (AbstractMethodError error) {
            String message = "Invalid Property object detected!";
            message += "\n  Class = " + property.getClass();
            message += "\n  Method not overriden: Property.parseValue(String)";
            RuntimeException exception = new RuntimeException(message, error);
            if (BCLib.DEV) {
                throw exception;
            } else {
                BCLog.logger.error("[silicon.facade] Invalid property!", exception);
            }
            return false;
        }

        boolean[] allFine = {true};
        property.getAllValues().forEach((a)-> {
            V value = a.value();
            String name = property.getName(value);
            Optional<V> optional = property.getValue(name);
            V parsed = optional == null ? null : optional.orElse(null);
            if (!Objects.equals(value, parsed)) {
                allFine[0] = false;
                // A property is *wrong*
                // this is a big problem
                String message = "Invalid property value detected!";
                message += "\n  Property class = " + property.getClass();
                message += "\n  Property = " + property;
                message += "\n  Possible Values = " + property.getAllValues();
                message += "\n  Value Name = " + name;
                message += "\n  Value (original) = " + value;
                message += "\n  Value (parsed) = " + parsed;
                message += "\n  Value class (original) = " + (value == null ? null : value.getClass());
                message += "\n  Value class (parsed) = " + (parsed == null ? null : parsed.getClass());
                if (optional == null) {
                    // Massive issue
                    message += "\n  Property.parseValue() -> Null com.google.common.base.Optional!!";
                }
                message += "\n";
                // Crash in a development environment so invalid properties are fixed early.
                // Release builds log the problem and skip the malformed property.
                RuntimeException exception = new RuntimeException(message);
                if (BCLib.DEV) {
                    throw exception;
                } else {
                    BCLog.logger.error("[silicon.facade] Invalid property!", exception);
                }
            }
        });
        return allFine[0];
    }

    private static String safeToString(Callable<Object> callable) {
        try {
            return Objects.toString(callable.call());
        } catch (Throwable t) {
            return "~~ERROR~~" + t.getMessage();
        }
    }

    @Override
    public Optional<ItemStack> createPhased(List<FacadePhase> phases, boolean hollow) {
        if (phases == null || phases.isEmpty() || phases.size() > 17) return Optional.empty();
        init();
        FacadePhasedState[] result = new FacadePhasedState[phases.size()];
        Set<DyeColor> colours = new HashSet<>();
        boolean uncoloured = false;
        for (int i = 0; i < result.length; i++) {
            FacadePhase phase = phases.get(i);
            if (phase == null) return Optional.empty();
            DyeColor colour = phase.activeColour();
            if (colour == null) {
                if (uncoloured) return Optional.empty();
                uncoloured = true;
            } else if (!colours.add(colour)) return Optional.empty();
            FacadeBlockStateInfo info = validFacadeStates.get(phase.state());
            if (info == null || !info.isVisible) return Optional.empty();
            result[i] = info.createPhased(colour);
        }
        return Optional.of(BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(new FacadeInstance(result, hollow)));
    }

    public Optional<FacadeStateResult> fromState(BlockState state) {
        if (state == null) return Optional.empty();
        FacadeBlockStateInfo info = validFacadeStates.get(state);
        return info == null ? Optional.empty() : Optional.of(toApiResult(info));
    }

    public Optional<FacadeStateResult> fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        ItemStack normalized = stack.copy();
        normalized.setCount(1);
        List<FacadeBlockStateInfo> infos = stackFacades.get(new ItemStackKey(normalized));
        if (infos == null || infos.isEmpty()) return Optional.empty();
        return Optional.of(toApiResult(infos.get(0)));
    }

    private static FacadeStateResult toApiResult(FacadeBlockStateInfo info) {
        return new FacadeStateResult(new FacadeMaterial(info.state, info.isTransparent), info.requiredStack);
    }


}
