/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import buildcraft.lib.platform.client.ClientModelBaking;
import java.util.Set;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/** Defines an object that will hold a model, and is automatically refreshed from the filesystem when the client reloads
 * all of its resources. */
public abstract class ModelHolder {
    public final ResourceLocation modelLocation;
    protected String failReason = "";

    public ModelHolder(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;
        if(this instanceof ModelHolderStatic)
        	ModelHolderRegistry.HOLDERS_VANILLABAKE.add(this);
        else
        	ModelHolderRegistry.HOLDERS_JSONBAKE.add(this);
    }

    public ModelHolder(String modelLocation) {
        this(ResourceLocation.parse(modelLocation));
    }
    
    public ModelResourceLocation getBakedModelLocation() {
        return new ModelResourceLocation(modelLocation, "standalone");
    }

    protected void onModelBakePre(ClientModelBaking.Additional event) {
        event.register(getBakedModelLocation());
    };

    protected abstract void onModelBake(ClientModelBaking.Completed event);

    protected abstract void onTextureStitch(Set<ResourceLocation> toRegisterSprites);

    public abstract boolean hasBakedQuads();
}
