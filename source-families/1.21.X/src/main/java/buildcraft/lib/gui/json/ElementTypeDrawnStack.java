package buildcraft.lib.gui.json;

import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.api.IExpressionNode.INodeBoolean;
import buildcraft.lib.gui.GuiStack;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.ISimpleDrawable;
import buildcraft.lib.gui.elem.GuiElementDrawable;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ElementTypeDrawnStack extends ElementType {
    public static final String NAME = "buildcraftlib:drawable/stack";
    public static final ElementTypeDrawnStack INSTANCE = new ElementTypeDrawnStack();

    private ElementTypeDrawnStack() {
        super(NAME);
    }

    @Override
    protected IGuiElement deserialize0(BuildCraftJsonGui gui, IGuiPosition parent, JsonGuiInfo info, JsonGuiElement json) {
        FunctionContext ctx = createContext(json);
        IGuiPosition pos = resolvePosition(json, "pos", parent, ctx);

        INodeBoolean visible = getEquationBool(json, "visible", ctx, true);
        boolean foreground = resolveEquationBool(json, "foreground", ctx, false);

        Item item = GsonHelper.getAsItem(json.json, "id").value();
        //int meta = resolveEquationInt(json, "meta", ctx);
        ItemStack stack = new ItemStack(item, 1/*, meta*/);

        ISimpleDrawable icon = new GuiStack(stack);
        IGuiArea area = IGuiArea.create(pos, 16, 16);
        return new GuiElementDrawable(gui, area, icon, foreground, visible);
    }
}
