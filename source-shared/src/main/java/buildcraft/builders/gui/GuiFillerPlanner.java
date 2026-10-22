/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.builders.gui;

import buildcraft.builders.filler.FillerStatementContext;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.builders.menu.ContainerFillerPlanner;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.button.IButtonBehaviour;
import buildcraft.lib.gui.button.IButtonClickEventListener;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.gui.json.BuildCraftJsonGui;
import buildcraft.lib.gui.json.InventorySlotHolder;
import buildcraft.lib.gui.json.SpriteDelegate;
import buildcraft.lib.misc.collect.TypedKeyMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Volume Box addon editor; the pattern palette and parameters are shared with the Filler GUI. */
public class GuiFillerPlanner extends GuiBC8<ContainerFillerPlanner> {
    private static final ResourceLocation LOCATION = ResourceLocation.tryParse("buildcraftbuilders:gui/filler_planner.json");
    private final SpriteDelegate patternSprite = new SpriteDelegate();

    public GuiFillerPlanner(ContainerFillerPlanner container, Inventory inventory, Component title) {
        //? if >=26.1 {
        super(container, gui -> new BuildCraftJsonGui(gui, BuildCraftGui.createWindowedArea(gui), LOCATION),
            inventory, title, 176, 81);
        //?} else {
        super(container, gui -> new BuildCraftJsonGui(gui, BuildCraftGui.createWindowedArea(gui), LOCATION),
            inventory, title);
        //?}
        BuildCraftJsonGui json = (BuildCraftJsonGui) mainGui;
        TypedKeyMap<String, Object> properties = json.properties;
        properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));
        FunctionContext context = json.context;
        properties.put("filler.possible", FillerStatementContext.CONTEXT_ALL);
        properties.put("filler.pattern", container.getPatternStatementClient());
        properties.put("filler.pattern.sprite", patternSprite);
        context.put_b("filler.invert", container::isInverted);
        context.put_b("filler.is_locked", container::isLocked);
        properties.put("filler.invert", IButtonBehaviour.TOGGLE);
        properties.put("filler.invert", container.isInverted());
        properties.put("filler.invert", (IButtonClickEventListener)
            (button, key) -> { if (!container.isLocked()) container.sendInverted(button.isButtonActive()); });
        // The dedicated screen has no item slots; the common JSON toolkit still needs
        // player.inventory for inherited elements and future theme updates.
        json.load();
        //? if <26.1 {
        imageWidth = json.getSizeX();
        imageHeight = json.getSizeY();
        //?}
        GuiHelpUtil.addRoot(mainGui, 8, 26, 160, 42, "buildcraft.help.filler.pattern.title",
            0xFF_66_AA_FF, "buildcraft.help.filler.pattern.desc");
    }

    @Override
    public void containerTick() {
        super.containerTick();
        container.getPatternStatementClient().canInteract = !container.isLocked();
        IFillerPattern pattern = container.getPatternStatementClient().get();
        patternSprite.delegate = pattern == null ? null : pattern.getSprite();
    }
}
