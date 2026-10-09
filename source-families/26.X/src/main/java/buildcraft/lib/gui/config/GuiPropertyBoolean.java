package buildcraft.lib.gui.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.expression.api.IVariableNode.IVariableNodeBoolean;

public class GuiPropertyBoolean extends GuiProperty implements IVariableNodeBoolean {

    private boolean value;

    public GuiPropertyBoolean(String name) {
        super(name);
    }

    // IVariableNodeBoolean

    public void set(boolean value) {
        this.value = value;
        GuiConfigManager.markDirty();
    }

    public boolean evaluate() {
        return value;
    }

    // GuiProperty

    public JsonElement writeToJson() {
        return new JsonPrimitive(value);
    }

    public void readFromJson(JsonElement json) {
        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
            BCLog.logger.warn("[lib.gui.config] Tried to read " + json + " as a boolean, but it wasn't!");
        } else {
            value = json.getAsBoolean();
        }
    }
}
