/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.lib;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.net.MessageContainer;
import buildcraft.lib.net.MessageDebugRequest;
import buildcraft.lib.net.MessageDebugResponse;
import buildcraft.lib.net.MessageGuideState;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.MessageMarker;
import buildcraft.lib.net.MessageUpdateTile;
import buildcraft.lib.net.cache.MessageObjectCacheRequest;
import buildcraft.lib.net.cache.MessageObjectCacheResponse;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;

public abstract class BCLibProxy {

    static void MessageRegistry() {
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageUpdateTile.class,
            MessageUpdateTile.HANDLER,
            MessageUpdateTile::toBytes,
            MessageUpdateTile::new
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageContainer.class,
            MessageContainer.HANDLER,
            MessageContainer::toBytes,
            MessageContainer::new
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageMarker.class,
            MessageMarker.HANDLER,
            MessageMarker::toBytes,
            MessageMarker::new,
            Dist.CLIENT
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageObjectCacheRequest.class,
            MessageObjectCacheRequest.HANDLER,
            MessageObjectCacheRequest::toBytes,
            MessageObjectCacheRequest::new,
            Dist.DEDICATED_SERVER
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageObjectCacheResponse.class,
            MessageObjectCacheResponse.HANDLER,
            MessageObjectCacheResponse::toBytes,
            MessageObjectCacheResponse::new,
            Dist.CLIENT
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageDebugRequest.class,
            MessageDebugRequest.HANDLER,
            MessageDebugRequest::toBytes,
            MessageDebugRequest::new,
            Dist.DEDICATED_SERVER
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageDebugResponse.class,
            MessageDebugResponse.HANDLER,
            MessageDebugResponse::toBytes,
            MessageDebugResponse::new,
            Dist.CLIENT
        );
        MessageManager.registerMessageClass(
            BCModules.LIB,
            MessageGuideState.class,
            MessageGuideState.HANDLER,
            MessageGuideState::toBytes,
            MessageGuideState::new,
            Dist.DEDICATED_SERVER
        );
    }

    static void fmlInit() {}

    void fmlPostInit() {}

    public Level getClientLevel() {
        return null;
    }

    public static Player getClientPlayer() {
        return FMLEnvironment.dist == Dist.CLIENT ? ClientAccess.getPlayer() : null;
    }

    @OnlyIn(Dist.CLIENT)
    private static final class ClientAccess {
        private ClientAccess() {
        }

        private static Player getPlayer() {
            return net.minecraft.client.Minecraft.getInstance().player;
        }
    }


    public <T extends BlockEntity> T getServerTile(T tile) {
        return tile;
    }

    public InputStream getStreamForIdentifier(ResourceLocation identifier) throws IOException {
        return null;
    }

    public abstract File getGameDirectory();

    public Iterable<File> getLoadedResourcePackFiles() {
        return Collections.emptySet();
    }
}
