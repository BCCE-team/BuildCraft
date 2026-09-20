/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import buildcraft.lib.platform.client.ClientAtlas;
import buildcraft.lib.platform.client.ClientModelBaking;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;

import net.minecraft.resources.ResourceLocation;

public class ModelHolderRegistry {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.model.holder");

    static final List<ModelHolder> HOLDERS_JSONBAKE = new ArrayList<>();
    static final List<ModelHolder> HOLDERS_VANILLABAKE = new ArrayList<>();

    /** Model-holder classes owned by optional BuildCraft modules. Their client setup listeners may run
     * concurrently with the first resource reload, so create the holders before collecting stitch sprites. */
    private static final String[] BUILTIN_HOLDER_CLASSES = {
        "buildcraft.factory.BCFactoryModels",
        "buildcraft.robotics.BCRoboticsModels",
        "buildcraft.silicon.BCSiliconModels",
        "buildcraft.transport.BCTransportModels"
    };

    public static void onTextureStitchPre(ClientAtlas.Before event) {
        bootstrapBuiltinHolders();
        Set<ResourceLocation> toStitch = new HashSet<>();
        for (ModelHolder holder : HOLDERS_JSONBAKE) {
            holder.onTextureStitch(toStitch);
        }

        for (ResourceLocation res : toStitch) {
        	event.addSprite(res);
        }
    }
    private static void bootstrapBuiltinHolders() {
        ClassLoader loader = ModelHolderRegistry.class.getClassLoader();
        for (String className : BUILTIN_HOLDER_CLASSES) {
            try {
                Class.forName(className, true, loader);
            } catch (ClassNotFoundException ignored) {
                // BuildCraft modules are independently optional.
            } catch (LinkageError | RuntimeException error) {
                BCLog.logger.error("[lib.model.holder] Failed to initialise model holder class " + className, error);
            }
        }
    }

	public static void preModelBake(ClientModelBaking.Additional event) {
        bootstrapBuiltinHolders();
        for (ModelHolder holder : HOLDERS_VANILLABAKE) {
            holder.onModelBakePre(event);
        }
	}
    
    public static void onModelBake(ClientModelBaking.Completed event) {
        for (ModelHolder holder : HOLDERS_JSONBAKE) {
            holder.onModelBake(event);
        }
        for (ModelHolder holder : HOLDERS_VANILLABAKE) {
            holder.onModelBake(event);
        }
        if (DEBUG) {
            BCLog.logger.info("[lib.model.holder] List of registered Models:");
            List<ModelHolder> holders = new ArrayList<>();
            holders.addAll(HOLDERS_JSONBAKE);
            holders.sort(Comparator.comparing(a -> a.modelLocation.toString()));

            for (ModelHolder holder : holders) {
                String status = "  ";
                if (holder.failReason != null) {
                    status += "(" + holder.failReason + ")";
                } else if (!holder.hasBakedQuads()) {
                    status += "(Model was registered too late)";
                }

                BCLog.logger.info("  - " + holder.modelLocation + status);
            }
            BCLog.logger.info("[lib.model.holder] Total of " + HOLDERS_JSONBAKE.size() + " models");
        }
    }
}
