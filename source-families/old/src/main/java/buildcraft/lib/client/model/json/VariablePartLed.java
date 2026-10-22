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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

public class VariablePartLed extends VariablePartCuboidBase {
    public static final ResourceLocation FALLBACK_SPRITE = new ResourceLocation("buildcraftlib", "model/led_fallback");
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

    @Override
    protected VariableFaceData getFaceData(Direction side, ITextureGetter spriteLookup) {
        FACE_DATA.uvs.minU = 1 / 16.0f;
        FACE_DATA.uvs.minV = 2 / 16.0f;
        FACE_DATA.uvs.maxU = 1 / 16.0f;
        FACE_DATA.uvs.maxV = 2 / 16.0f;
        return FACE_DATA;
    }
    
    public static void onTextureStitchPost(ClientAtlas.After event) {
    	if(InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
            FACE_DATA.sprite = event.getAtlas().getSprite(FALLBACK_SPRITE);
    	}

    }
}
