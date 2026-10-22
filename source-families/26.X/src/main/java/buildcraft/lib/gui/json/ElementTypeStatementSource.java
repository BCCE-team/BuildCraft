package buildcraft.lib.gui.json;

import com.google.gson.JsonSyntaxException;

import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.pos.IGuiPosition;
import buildcraft.lib.gui.statement.GuiElementStatementSource;
import buildcraft.lib.statement.StatementContext;

public class ElementTypeStatementSource extends ElementType {
    public static final String NAME = "buildcraftlib:statement/source";
    public static final ElementTypeStatementSource INSTANCE = new ElementTypeStatementSource();

    // source: A link to a StatementContext element

    private ElementTypeStatementSource() {
        super(NAME);
    }

    public IGuiElement deserialize0(BuildCraftJsonGui gui, IGuiPosition parent, JsonGuiInfo info, JsonGuiElement json) {
        FunctionContext ctx = createContext(json);

        String source = json.properties.get("source");

        StatementContext<?> ctxSource = gui.properties.get(source, StatementContext.class);

        String side = json.properties.get("side");
        String style = json.properties.get("style");
        if (style == null || "flat".equals(style)) {
            return new GuiElementStatementSource<>(gui, !"right".equals(side), ctxSource);
        } else {
            throw new JsonSyntaxException("Unknown style '" + style + "'");
        }
    }
}
