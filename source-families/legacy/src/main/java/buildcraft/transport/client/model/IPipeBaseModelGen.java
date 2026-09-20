/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.model;

import buildcraft.lib.platform.client.ClientAtlas;
import java.util.List;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if <1.20 {
//?}

import buildcraft.transport.internal.pipe.PipeDefinition;

import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseCutoutKey;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseTranslucentKey;

public interface IPipeBaseModelGen {
//    DetailedConfigOption OPTION_INSIDE_COLOUR_MULT = new DetailedConfigOption("render.pipe.misc.inside.shade", "0.725");

    List<BakedQuad> generateCutout(PipeBaseCutoutKey key);

    List<BakedQuad> generateTranslucent(PipeBaseTranslucentKey key);

    TextureAtlasSprite[] getItemSprites(PipeDefinition def);

    //? if <1.20 {
    void onTextureStitchPre(ClientAtlas.Before event);
    //?}
}
