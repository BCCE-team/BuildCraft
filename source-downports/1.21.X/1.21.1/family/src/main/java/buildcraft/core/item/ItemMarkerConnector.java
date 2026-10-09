/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.core.item;

import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.ImmutableList;

import buildcraft.core.marker.PathSubCache;
import buildcraft.core.marker.VolumeSubCache;
import buildcraft.core.marker.volume.Addon;
import buildcraft.core.marker.volume.EnumAddonSlot;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import buildcraft.core.marker.volume.VolumeBoxToolActions;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerSubCache;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.PositionUtil;
import buildcraft.lib.misc.PositionUtil.Line;
import buildcraft.lib.misc.PositionUtil.LineSkewResult;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ItemMarkerConnector extends Item {

    private static final ResourceLocation ADVANCEMENT_VOLUME_MARKER = ResourceLocation.parse("buildcraftcore:markers");
    private static final ResourceLocation ADVANCEMENT_PATH_MARKER = ResourceLocation.parse("buildcraftcore:path_markers");

    public ItemMarkerConnector(Properties prop) {
        super(prop);
    }

    @Override
	public InteractionResultHolder<net.minecraft.world.item.ItemStack> use(Level world, Player player, InteractionHand hand) {
        if (!world.isClientSide) {
            for (MarkerCache<?> cache : MarkerCache.CACHES) {
                if (interactCache(cache.getSubCache(world), player)) {
                    player.swing(hand);
                    break;
                }
            }
        }
        return new InteractionResultHolder<>(onItemRightClickVolumeBoxes(world, player), player.getItemInHand(hand));
	}



    private static <S extends MarkerSubCache<?>> boolean interactCache(S cache, Player player) {
        MarkerLineInteraction best = null;
        Vec3 playerPos = player.getEyePosition();
        Vec3 playerLook = player.getLookAngle();
        for (BlockPos marker : cache.getAllMarkers()) {
            ImmutableList<BlockPos> possibles = cache.getValidConnections(marker);
            for (BlockPos possible : possibles) {
                MarkerLineInteraction interaction = new MarkerLineInteraction(marker, possible, playerPos, playerLook);
                if (interaction.didInteract()) {
                    best = interaction.getBetter(best);
                }
            }
        }
        if (best == null) {
            return false;
        }
        if (cache.tryConnect(best.marker1, best.marker2) || cache.tryConnect(best.marker2, best.marker1)) {
            if (cache instanceof VolumeSubCache) {
                AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_VOLUME_MARKER);
            } else if (cache instanceof PathSubCache) {
                AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_PATH_MARKER);
            }
            return true;
        }
        return false;
    }

    public static boolean doesInteract(BlockPos a, BlockPos b, Player player) {
        return new MarkerLineInteraction(
            a,
            b,
            player.getEyePosition(),
            player.getLookAngle()
        ).didInteract();
    }

    /** Volume Box editing belongs to the Marker Connector, never the box item. */
    private InteractionResult onItemRightClickVolumeBoxes(Level world, Player player) {
        return VolumeBoxToolActions.use(world, player);
    }

    private static class MarkerLineInteraction {
        public final BlockPos marker1, marker2;
        public final double distToPoint, distToLine;

        public MarkerLineInteraction(BlockPos marker1, BlockPos marker2, Vec3 playerPos, Vec3 playerEndPos) {
            this.marker1 = marker1;
            this.marker2 = marker2;
            LineSkewResult interactionPoint = PositionUtil.findLineSkewPoint(
                new Line(
                    VecUtil.convertCenter(marker1),
                    VecUtil.convertCenter(marker2)
                ),
                playerPos,
                playerEndPos
            );
            distToPoint = interactionPoint.closestPos.distanceTo(playerPos);
            distToLine = interactionPoint.distFromLine;
        }

        public boolean didInteract() {
            return distToPoint <= 3 && distToLine < 0.3;
        }

        public MarkerLineInteraction getBetter(MarkerLineInteraction other) {
            if (other == null) {
                return this;
            }
            if (other.marker1.equals(marker2) && other.marker2.equals(marker1)) {
                return other;
            }
            if (other.distToLine < distToLine) {
                return other;
            }
            if (other.distToLine > distToLine) {
                return this;
            }
            if (other.distToPoint < distToPoint) {
                return other;
            }
            return this;
        }
    }
}
