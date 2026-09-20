/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.stripes;

import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeExtensionManager;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStripes;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

public enum PipeExtensionManager implements IPipeExtensionManager {
    INSTANCE;

    private final Map<ResourceKey<Level>, List<PipeExtensionRequest>> requests = new HashMap<>();
    private final Set<PipeDefinition> retractionPipeDefs = new HashSet<>();

    @Override
    public boolean requestPipeExtension(Level world, BlockPos pos, Direction dir, StripesOutput stripes, ItemStack stack) {
        if (world.isClientSide || stack.isEmpty() || !(stack.getItem() instanceof IItemPipe)) {
            return false;
        }
        ResourceKey<Level> id = world.dimension();
        List<PipeExtensionRequest> rList = requests.get(id);
        if (rList == null) {
            requests.put(id, rList = new ArrayList<>());
        }
        return rList.add(new PipeExtensionRequest(pos, dir, stripes, ((IItemPipe) stack.getItem()).getDefinition(), stack.copy()));
    }

    @Override
    public void registerRetractionPipe(PipeDefinition pipeDefinition) {
        if (pipeDefinition != null) {
            retractionPipeDefs.add(pipeDefinition);
        }
    }


    public void tick(BCEvents.LevelTick event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        List<PipeExtensionRequest> rList = requests.get(event.getLevel().dimension());
        if (rList == null) {
            return;
        }
        for (PipeExtensionRequest r : rList) {
            if (!isCurrentSource(event.getLevel(), r)) {
                refundStaleRequest(event.getLevel(), r);
                continue;
            }
            if (retractionPipeDefs.contains(r.pipeDef)) {
                retract(event.getLevel(), r);
            } else {
                extend(event.getLevel(), r);
            }
        }
        rList.clear();
    }

    private boolean isCurrentSource(Level world, PipeExtensionRequest request) {
        if (!world.hasChunkAt(request.pos)) return false;
        IPipeHolder holder = CapUtil.getCapability(world, request.pos, PipeApi.CAP_PIPE_HOLDER, null);
        return holder != null && holder.getPipe() != null && holder.getPipe().getBehaviour() == request.stripes;
    }

    private void refundStaleRequest(Level world, PipeExtensionRequest request) {
        BCLog.logger.warn("Discarding stale Stripes pipe-extension request at " + request.pos + " and returning its pipe item");
        InventoryUtil.drop(world, request.pos, request.stack.copy());
    }

    private void retract(Level w, PipeExtensionRequest r) {
        Direction retractDir = r.dir.getOpposite();
        if (!isValidRetractionPath(w, r, retractDir)) {

            // check other directions
            List<Direction> possible = new ArrayList<>();
            for (Direction facing : Direction.values()) {
                if (facing.getAxis() != r.dir.getAxis()) {
                    if (isValidRetractionPath(w, r, facing)) {
                        possible.add(facing);
                    }
                }
            }

            if (possible.isEmpty()) {
                r.stripes.sendItem(r.stack.copy(), r.dir);
                return;
            }
            retractDir = possible.get(Mth.nextInt(w.random, 0, possible.size() - 1));
        }
        BlockPos p = r.pos.offset(retractDir.getNormal());

        NonNullList<ItemStack> stacksToSendBack = NonNullList.create();
        // Always send back catalyst pipe
        stacksToSendBack.add(r.stack);

        // Step 1: Copy over existing stripes pipe
        BlockSnapshot blockSnapshot1 = BlockSnapshot.create(w.dimension(), w, r.pos);
        BlockState stripesStateOld = w.getBlockState(r.pos);
        BlockEntity stripesTileOld = w.getBlockEntity(r.pos);
        final GameProfile owner;
        // Fetch owner
        {
            IPipeHolder holder = CapUtil.getCapability(w, r.pos, PipeApi.CAP_PIPE_HOLDER, null);
            if (stripesTileOld == null || holder == null || holder.getPipe() == null || holder.getPipe().getBehaviour() != r.stripes) {
                BCLog.logger
                    .warn("Found a stale request at " + r.pos + " because its original Stripes pipe no longer exists");
                refundStaleRequest(w, r);
                return;
            }
            owner = holder.getOwner();
            PipeBehaviour behaviour = holder.getPipe().getBehaviour();
            if (behaviour instanceof PipeBehaviourStripes) {
                ((PipeBehaviourStripes) behaviour).direction = retractDir.getOpposite();
            }
        }

        CompoundTag stripesNBTOld = stripesTileOld.saveWithFullMetadata(w.registryAccess());

        // Step 2: Remove previous pipe
        BlockSnapshot blockSnapshot2 = BlockSnapshot.create(w.dimension(), w, p);
        NonNullList<ItemStack> list = NonNullList.create();
        boolean canceled = !BlockUtil.breakBlock((ServerLevel) w, p, list, r.pos, owner);
        if (canceled) {
            blockSnapshot2.restore();
            BlockEntity tile = w.getBlockEntity(p);
            if (tile != null) {
                tile.onLoad();
            }
        }

        // Step 3: Place the stripes pipe and remove the replaced pipe
        if (!canceled) {
            // - Correct NBT coordinates
            stripesNBTOld.putInt("x", p.getX());
            stripesNBTOld.putInt("y", p.getY());
            stripesNBTOld.putInt("z", p.getZ());

            // - Create block and tile
            ServerPlayer player = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) w, owner, p);
            player.getInventory().clearContent();
            w.setBlock(p, stripesStateOld, 3);
            if (EventHooks.onBlockPlace(player, blockSnapshot2, r.dir)) {
                canceled = true;
                blockSnapshot2.restore();
                BlockEntity tile = w.getBlockEntity(r.pos);
                if (tile != null) {
                    tile.onLoad();
                }
            } else {
                SoundUtil.playBlockBreak(w, p, blockSnapshot2.getState());

                canceled = !BlockUtil.breakBlock((ServerLevel) w, r.pos, NonNullList.create(), r.pos, owner);
                if (canceled) {
                    blockSnapshot1.restore();
                    BlockEntity tile1 = w.getBlockEntity(r.pos);
                    if (tile1 != null) {
                        tile1.onLoad();
                    }

                    blockSnapshot2.restore();
                    BlockEntity tile2 = w.getBlockEntity(p);
                    if (tile2 != null) {
                        tile2.onLoad();
                    }
                } else {
                    stacksToSendBack.addAll(list);
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        ItemStack stack = player.getInventory().removeItemNoUpdate(i);
                        if (!stack.isEmpty()) {
                            stacksToSendBack.add(stack);
                        }
                    }
                }
            }
        }

        // Step 4: Hope for the best, clean up.
        cleanup(w, r, p, stacksToSendBack, canceled, stripesNBTOld);
    }

    private void extend(Level w, PipeExtensionRequest r) {
        BlockPos p = r.pos.offset(r.dir.getNormal());
        if (!w.isEmptyBlock(p) && !w.getBlockState(p).canBeReplaced()) {
            r.stripes.sendItem(r.stack.copy(), r.dir);
            return;
        }

        NonNullList<ItemStack> stacksToSendBack = NonNullList.create();

        // Step 1: Copy over and remove existing stripes pipe
        BlockState stripesStateOld = w.getBlockState(r.pos);
        CompoundTag stripesNBTOld = new CompoundTag();
        BlockEntity stripesTileOld = w.getBlockEntity(r.pos);
        final GameProfile owner;
        // Fetch owner
        {
            IPipeHolder holder = CapUtil.getCapability(w, r.pos, PipeApi.CAP_PIPE_HOLDER, null);
            if (stripesTileOld == null || holder == null || holder.getPipe() == null || holder.getPipe().getBehaviour() != r.stripes) {
                BCLog.logger.warn("Found a stale request at " + r.pos + " because its original Stripes pipe no longer exists");
                refundStaleRequest(w, r);
                return;
            }
            owner = holder.getOwner();
        }

        stripesNBTOld = stripesTileOld.saveWithFullMetadata(w.registryAccess());
        BlockSnapshot blockSnapshot1 = BlockSnapshot.create(w.dimension(),w, r.pos);
        boolean canceled = !BlockUtil.breakBlock((ServerLevel) w, r.pos, NonNullList.create(), r.pos, owner);
        if (canceled) {
            stacksToSendBack.add(r.stack);

            blockSnapshot1.restore();
            BlockEntity tile = w.getBlockEntity(r.pos);
            if (tile != null) {
                tile.onLoad();
            }
        }

        NonNullList<ItemStack> list = NonNullList.create();

        // Step 2: Add new pipe
        if (!canceled) {
            ServerPlayer player = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) w, owner, r.pos);
            player.getInventory().clearContent();
            player.getInventory().setItem(player.getInventory().selected, r.stack);
            InteractionResult result = CommonHooks.onPlaceItemIntoWorld(
                    new UseOnContext(player.level(), player, InteractionHand.MAIN_HAND,r.stack ,new BlockHitResult(new Vec3(0.5f,0.5f,0.5f), r.dir.getOpposite(), r.pos, false)));
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().removeItemNoUpdate(i);
                if (!stack.isEmpty()) {
                    list.add(stack);
                }
            }
            if (canceled = result != InteractionResult.CONSUME) {
                blockSnapshot1.restore();
                BlockEntity tile = w.getBlockEntity(r.pos);
                if (tile != null) {
                    tile.onLoad();
                }
            }
        }

        // Step 3: Place stripes pipe back
        if (!canceled) {
            // - Correct NBT coordinates
            stripesNBTOld.putInt("x", p.getX());
            stripesNBTOld.putInt("y", p.getY());
            stripesNBTOld.putInt("z", p.getZ());

            // - Create block and tile
            ServerPlayer player = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) w, owner, p);
            player.getInventory().clearContent();
            BlockSnapshot blockSnapshot2 = BlockSnapshot.create(w.dimension(), w, p);
            w.setBlock(p, stripesStateOld, 3);
            if (EventHooks.onBlockPlace(player, blockSnapshot2, r.dir.getOpposite())) {
                canceled = true;
                stacksToSendBack.add(r.stack);

                blockSnapshot1.restore();
                BlockEntity tile = w.getBlockEntity(r.pos);
                if (tile != null) {
                    tile.onLoad();
                }

                blockSnapshot2.restore();
            } else {
                stacksToSendBack.addAll(list);
            }
        } else {
            stacksToSendBack.addAll(list);
        }

        // Step 4: Hope for the best, clean up.
        cleanup(w, r, p, stacksToSendBack, canceled, stripesNBTOld);
    }

    private void cleanup(Level w, PipeExtensionRequest r, BlockPos p, NonNullList<ItemStack> stacksToSendBack, boolean canceled, CompoundTag stripesNBTOld) {
        BlockPos stripesPosNew = canceled ? r.pos : p;
        BlockEntity stripesTileNew = w.getBlockEntity(stripesPosNew);
        if (stripesTileNew == null) {
            // Odd.
            // Maybe it would be better to crash?
            InventoryUtil.dropAll(w, p, stacksToSendBack);
            return;
        }
        if (!canceled) {
            stripesTileNew.loadWithComponents(stripesNBTOld, w.registryAccess());
            stripesTileNew.onLoad();
        }

        IPipeHolder stripesPipeHolderNew = CapUtil.getCapability(w, stripesPosNew, PipeApi.CAP_PIPE_HOLDER, null);
        if (stripesPipeHolderNew != null) {
            if (!canceled) {
                stripesPipeHolderNew.getWireManager().getWireSystems().rebuildWireSystemsAround(stripesPipeHolderNew);
            }

            PipeBehaviour behaviour = stripesPipeHolderNew.getPipe().getBehaviour();
            if (behaviour instanceof StripesOutput) {
                StripesOutput stripesNew = (StripesOutput) behaviour;
                for (ItemStack s : stacksToSendBack) {
                    s = s.copy();
                    if (!stripesNew.sendItem(s, r.dir)) {
                        stripesNew.dropItem(s, r.dir);
                    }
                }
            } else {
                InventoryUtil.dropAll(w, p, stacksToSendBack);
            }
        } else {
            InventoryUtil.dropAll(w, p, stacksToSendBack);
        }
    }

    private boolean isValidRetractionPath(Level w, PipeExtensionRequest r, Direction retractDir) {
        BlockPos pipePos = r.pos.offset(retractDir.getNormal());
        IPipe pipe = CapUtil.getCapability(w, pipePos, PipeApi.CAP_PIPE, null);
        if (pipe != null) {
            boolean connected = false;
            for (Direction facing : Direction.values()) {
                if (pipe.getConnectedType(facing) == IPipe.ConnectedType.TILE) {
                    return false;
                }
                if (facing == retractDir.getOpposite() && pipe.getConnectedType(facing) != IPipe.ConnectedType.PIPE) {
                    return false;
                }
                if (facing != retractDir.getOpposite() && connected && pipe.getConnectedType(facing) != null) {
                    return false;
                }
                if (facing != retractDir.getOpposite() && !connected && pipe.getConnectedType(facing) != null) {
                    connected = true;
                }

            }
            return true;
        }
        return false;
    }

    private class PipeExtensionRequest {
        public final BlockPos pos;
        public final Direction dir;
        public final StripesOutput stripes;
        public final PipeDefinition pipeDef;
        public final ItemStack stack;

        private PipeExtensionRequest(BlockPos pos, Direction dir, StripesOutput stripes, PipeDefinition pipeDef, ItemStack stack) {
            this.pos = pos;
            this.dir = dir;
            this.stripes = stripes;
            this.pipeDef = pipeDef;
            this.stack = stack;
        }
    }
    private static boolean gameplayEventsRegistered;
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, INSTANCE::tick);
    }
}
