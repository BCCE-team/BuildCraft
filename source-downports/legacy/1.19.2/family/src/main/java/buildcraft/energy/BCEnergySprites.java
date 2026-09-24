/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy;

import buildcraft.lib.platform.client.ClientAtlas;
import net.minecraft.resources.ResourceLocation;

public class BCEnergySprites {

	public static final ResourceLocation fuel_flow =  new ResourceLocation("buildcraftenergy:blocks/fluids/fuel_flow");
	public static final ResourceLocation fuel_still = new ResourceLocation("buildcraftenergy:blocks/fluids/fuel_still");
	public static final ResourceLocation oil_flow = new ResourceLocation("buildcraftenergy:blocks/fluids/oil_flow");
	public static final ResourceLocation oil_still = new ResourceLocation("buildcraftenergy:blocks/fluids/oil_still");
	
	public static final ResourceLocation IRON_BACK_R = new ResourceLocation("buildcraftenergy:blocks/engine/iron/back");
	public static final ResourceLocation IRON_SIDE_R = new ResourceLocation("buildcraftenergy:blocks/engine/iron/side");
	public static final ResourceLocation STONE_BACK_R = new ResourceLocation("buildcraftenergy:blocks/engine/stone/back");
	public static final ResourceLocation STONE_SIDE_R = new ResourceLocation("buildcraftenergy:blocks/engine/stone/side");
	public static final ResourceLocation FE_BACK_R = new ResourceLocation("buildcraftenergy:blocks/engine/fe/back");
	public static final ResourceLocation FE_SIDE_R = new ResourceLocation("buildcraftenergy:blocks/engine/fe/side");
	public static final ResourceLocation DYNAMO_BACK_R = new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/back");
	public static final ResourceLocation DYNAMO_FRONT_R = new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/front");
	public static final ResourceLocation DYNAMO_SIDE_R = new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/side");

	public static final ResourceLocation ENGINE_IRON_GUI = new ResourceLocation("buildcraftenergy:textures/gui/combustion_engine_gui.png");
	public static final ResourceLocation ENGINE_STONE_GUI = new ResourceLocation("buildcraftenergy:textures/gui/steam_engine_gui.png");
	public static final ResourceLocation ENGINE_FE_GUI = new ResourceLocation("buildcraftenergy:textures/gui/fe_engine_gui.png");
	public static final ResourceLocation DYNAMO_MJ_GUI = new ResourceLocation("buildcraftenergy:textures/gui/mj_dynamo_gui.png");
	
    public static void onTextureStitchPre(ClientAtlas.Before event) {
       
        if("textures/atlas/blocks.png".equals(event.getAtlas().location().getPath())) {
//    		
        	for (int h = 0; h < 3; h++) {
        		event.addSprite(new ResourceLocation("buildcraftenergy:blocks/fluids/heat_" + h + "_still"));
        		event.addSprite(new ResourceLocation("buildcraftenergy:blocks/fluids/heat_" + h + "_flow"));
            }
//        	event.addSprite(fuel_flow);
//            event.addSprite(fuel_still);
//            event.addSprite(oil_flow);
//            event.addSprite(oil_still);
    		event.addSprite(IRON_BACK_R);
    		event.addSprite(IRON_SIDE_R);
    		event.addSprite(STONE_BACK_R);
    		event.addSprite(STONE_SIDE_R);
		event.addSprite(FE_BACK_R);
		event.addSprite(FE_SIDE_R);
		event.addSprite(DYNAMO_BACK_R);
		event.addSprite(DYNAMO_FRONT_R);
		event.addSprite(DYNAMO_SIDE_R);
    		
        }
    }
}
