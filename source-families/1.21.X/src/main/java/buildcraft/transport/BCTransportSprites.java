/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import java.util.EnumMap;
import java.util.Locale;

import buildcraft.lib.client.sprite.SpriteHolderRegistry;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli.SlotIndex;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

public class BCTransportSprites {

	
	public static final ResourceLocation DIAWOOD_GUI = ResourceLocation.parse("buildcrafttransport:textures/gui/pipe_emerald.png");
	public static final ResourceLocation DIAWOOD_BUTTON_GUI = ResourceLocation.parse("buildcrafttransport:textures/gui/pipe_emerald_button.png");
	
	public static final ResourceLocation DIAMOND_GUI = ResourceLocation.parse("buildcrafttransport:textures/gui/filter.png");
	
	public static final ResourceLocation FILTERED_BUFFER_GUI = ResourceLocation.parse("buildcrafttransport:textures/gui/filtered_buffer.png");
	public static final ResourceLocation FILTERED_BUFFER_NOTTHING_SLOT_GUI = ResourceLocation.parse("buildcrafttransport:gui/nothing_filtered_buffer_slot");
	public static final ResourceLocation FILTERED_BUFFER_EMPTY_SLOT_GUI = ResourceLocation.parse("buildcrafttransport:gui/empty_filtered_buffer_slot");
	
	public static final ResourceLocation EMZULI_GUI = ResourceLocation.parse("buildcrafttransport:textures/gui/pipe_emzuli.png");
    public static final SpriteHolder PIPE_COLOUR, COLOUR_ITEM_BOX;
    public static final SpriteHolder PIPE_COLOUR_BORDER_OUTER;
    public static final SpriteHolder PIPE_COLOUR_BORDER_INNER;

    public static final SpriteHolder TRIGGER_POWER_REQUESTED;
    public static final SpriteHolder TRIGGER_ITEMS_TRAVERSING;
    public static final SpriteHolder TRIGGER_FLUIDS_TRAVERSING;
    
    public static final SpriteHolder[] ACTION_PIPE_COLOUR;
    public static final EnumMap<SlotIndex, SpriteHolder> ACTION_EXTRACTION_PRESET;
    private static final EnumMap<DyeColor, SpriteHolder> PIPE_SIGNAL_ON;
    private static final EnumMap<DyeColor, SpriteHolder> PIPE_SIGNAL_OFF;
    private static final EnumMap<Direction, SpriteHolder> ACTION_PIPE_DIRECTION;

    public static final SpriteHolder POWER_FLOW;
    public static final SpriteHolder POWER_FLOW_OVERLOAD;
    public static final SpriteHolder[] POWER_LIMIT;
    public static final SpriteHolder[] FE_LIMIT;
    
    static {
        PIPE_COLOUR = getHolder("pipes/overlay_stained");
        COLOUR_ITEM_BOX = getHolder("pipes/colour_item_box");
        PIPE_COLOUR_BORDER_OUTER = getHolder("pipes/colour_border_outer");
        PIPE_COLOUR_BORDER_INNER = getHolder("pipes/colour_border_inner");

        ACTION_PIPE_COLOUR = new SpriteHolder[ColourUtil.COLOURS.length];
        for (DyeColor colour : ColourUtil.COLOURS) {
            ACTION_PIPE_COLOUR[colour.ordinal()] = getHolder("core", "items/paintbrush/" + colour.getName());
        }

        PIPE_SIGNAL_OFF = new EnumMap<>(DyeColor.class);
        PIPE_SIGNAL_ON = new EnumMap<>(DyeColor.class);

        for (DyeColor colour : ColourUtil.COLOURS) {
            String pre = "triggers/trigger_pipesignal_" + colour.getName().toLowerCase(Locale.ROOT) + "_";
            PIPE_SIGNAL_OFF.put(colour, getHolder(pre + "inactive"));
            PIPE_SIGNAL_ON.put(colour, getHolder(pre + "active"));
        }

        ACTION_EXTRACTION_PRESET = new EnumMap<>(SlotIndex.class);
        for (SlotIndex index : SlotIndex.VALUES) {
            ACTION_EXTRACTION_PRESET.put(index, getHolder("triggers/extraction_preset_" + index.colour.getName()));
        }

        ACTION_PIPE_DIRECTION = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            ACTION_PIPE_DIRECTION.put(face,
                getHolder("core", "triggers/trigger_dir_" + face.getName().toLowerCase(Locale.ROOT)));
        }

        POWER_FLOW = getHolder("pipes/power_flow");
        POWER_FLOW_OVERLOAD = getHolder("pipes/power_flow_overload");

        POWER_LIMIT = new SpriteHolder[] {
            getHolder("triggers/trigger_limiter_m256"),
            getHolder("triggers/trigger_limiter_m128"),
            getHolder("triggers/trigger_limiter_m64"),
            getHolder("triggers/trigger_limiter_m16"),
            getHolder("triggers/trigger_limiter_m8"),
            getHolder("triggers/trigger_limiter_m2"),
            getHolder("triggers/trigger_limiter_m0"),
        };

        FE_LIMIT = new SpriteHolder[] {
            getHolder("triggers/trigger_fe_limiter_m256"),
            getHolder("triggers/trigger_fe_limiter_m128"),
            getHolder("triggers/trigger_fe_limiter_m64"),
            getHolder("triggers/trigger_fe_limiter_m16"),
            getHolder("triggers/trigger_fe_limiter_m8"),
            getHolder("triggers/trigger_fe_limiter_m2"),
            getHolder("triggers/trigger_fe_limiter_m0"),
        };

        TRIGGER_POWER_REQUESTED = getHolder("transport", "triggers/trigger_pipecontents_requestsenergy");
        TRIGGER_ITEMS_TRAVERSING = getHolder("transport", "triggers/trigger_pipecontents_containsitems");
        TRIGGER_FLUIDS_TRAVERSING = getHolder("transport", "triggers/trigger_pipecontents_containsfluids");
    }
	
    private static SpriteHolder getHolder(String loc) {
        return SpriteHolderRegistry.getHolder("buildcrafttransport:" + loc);
    }

    private static SpriteHolder getHolder(String module, String loc) {
        return SpriteHolderRegistry.getHolder("buildcraft" + module + ":" + loc);
    }
    public static SpriteHolder getPipeSignal(boolean active, DyeColor colour) {
        return (active ? PIPE_SIGNAL_ON : PIPE_SIGNAL_OFF).get(colour);
    }

    public static SpriteHolder getPipeDirection(Direction face) {
        return ACTION_PIPE_DIRECTION.get(face);
    }

}
