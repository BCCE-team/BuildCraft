/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model.json;

import buildcraft.lib.platform.client.ClientAtlas;
import com.google.gson.JsonObject;

import buildcraft.lib.client.model.json.JsonVariableModel.ITextureGetter;
import buildcraft.lib.expression.FunctionContext;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;

public class VariablePartLed extends VariablePartCuboidBase {
    public static final Identifier FALLBACK_SPRITE = Identifier.fromNamespaceAndPath("buildcraftlib", "model/led_fallback");
    private static final VariableFaceData FACE_DATA = new VariableFaceData();

    static {
//    	FACE_DATA.sprite = ModelLoader.White.INSTANCE;
        FACE_DATA.uvs.minU = 1 / 16.0f;
        FACE_DATA.uvs.minV = 2 / 16.0f;
        FACE_DATA.uvs.maxU = 1 / 16.0f;
        FACE_DATA.uvs.maxV = 2 / 16.0f;
    }

    public VariablePartLed(JsonObject obj, FunctionContext fnCtx) {
        super(obj, fnCtx);
    }

    protected VariableFaceData getFaceData(Direction side, ITextureGetter spriteLookup) {
        // Resource reload can briefly request item quads before atlas publication.
        // Never create a quad with a null sprite; the next atlas-ready pass
        // invalidates item-model caches and rebuilds these LED faces.
        if (FACE_DATA.sprite == null) return null;
        FACE_DATA.uvs.minU = 1 / 16.0f;
        FACE_DATA.uvs.minV = 2 / 16.0f;
        FACE_DATA.uvs.maxU = 1 / 16.0f;
        FACE_DATA.uvs.maxV = 2 / 16.0f;
        return FACE_DATA;
    }
    
    public static void onTextureStitchPost(ClientAtlas.After event) {
    	if(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS.equals(event.getAtlas().location())) {
            var sprite = event.getAtlas().getSprite(FALLBACK_SPRITE);
            FACE_DATA.sprite = sprite != null ? sprite
                : event.getAtlas().getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
    	}

    }
}
