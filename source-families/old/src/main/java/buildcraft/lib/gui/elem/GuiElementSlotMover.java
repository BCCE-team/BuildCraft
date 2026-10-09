package buildcraft.lib.gui.elem;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=1.20 {
/*?
import net.minecraft.client.gui.GuiGraphics;
?*/
//?}

import buildcraft.lib.expression.api.IExpressionNode.INodeBoolean;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.world.inventory.Slot;

/** Moves and hides a menu slot according to a JSON GUI element. */
public class GuiElementSlotMover extends GuiElementSimple {

    private static final int HIDDEN_SLOT_POSITION = -10_000;

    public final INodeBoolean visible;
    public final Slot toMove;

    public GuiElementSlotMover(BuildCraftGui gui, IGuiPosition pos, INodeBoolean visible, Slot toMove) {
        super(gui, IGuiArea.create(pos, 18, 18));
        this.visible = visible;
        this.toMove = toMove;
    }

    @Override
    //? if <1.20 {
    public void drawBackground(PoseStack pose, float partialTicks) {
    //?} else {
    /*?
    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
    ?*/
    //?}
        if (visible.evaluate()) {
            toMove.x = 1 + (int) Math.round(getX());
            // Slot JSON uses the interior Y coordinate directly.
            toMove.y = (int) Math.round(getY());
        } else {
            toMove.x = HIDDEN_SLOT_POSITION;
            toMove.y = HIDDEN_SLOT_POSITION;
        }
    }
}
