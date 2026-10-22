/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.stripes;

import java.util.Collections;
import java.util.List;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.misc.AutomationPermissionUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public enum StripesHandlerEntityInteract {
    INSTANCE;

    public boolean handle(Level world,
                          BlockPos pos,
                          Direction direction,
                          ItemStack stack,
                          Player player,
                          StripesOutput activator) {
        List<LivingEntity> entities = world.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(pos.offset(direction.getNormal()))
        );
        Collections.shuffle(entities);
        for (LivingEntity entity : entities) {
            if (!AutomationPermissionUtil.mayEntity(
                world, pos, entity, player.getGameProfile(), AutomationPermissionUtil.SOURCE_STRIPES_PIPE,
                WorldOperationKind.ENTITY_INTERACT, OperationMode.EXECUTE
            )) {
                continue;
            }
            if (player.interactOn(entity, InteractionHand.MAIN_HAND, entity.position()).consumesAction()) {
                return true;
            }
        }
        return false;
    }
}
