package buildcraft.silicon.gui;

import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.button.IButtonBehaviour;
import buildcraft.lib.gui.button.IButtonClickEventListener;
import buildcraft.lib.gui.json.BuildCraftJsonGui;
import buildcraft.lib.gui.json.InventorySlotHolder;
import buildcraft.lib.misc.collect.TypedKeyMap;
import buildcraft.silicon.container.ContainerGate;
import buildcraft.silicon.gate.GateLogic;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiGate extends GuiBC8<ContainerGate> {

    public static final Identifier GUI_DEFINITION = Identifier.parse("buildcraftsilicon:gui/gate.json");

    public GuiGate(ContainerGate container, Inventory inv, Component title) {
        super(container, gui -> {
            BuildCraftJsonGui json = new BuildCraftJsonGui(gui, BuildCraftGui.createWindowedArea(gui), GUI_DEFINITION);
            json.properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));
            return json;
        }, inv, title, 176, 117 + Math.max(1, container.slotHeight) * 18);

        BuildCraftJsonGui jsonGui = (BuildCraftJsonGui) mainGui;
        preLoad(jsonGui);
        jsonGui.load();
        addStatementHelpZones();
    }

    private void addStatementHelpZones() {
        GateLogic gate = container.gate;
        int horizontalSlotCount = gate.isSplitInTwo() ? 2 : 1;
        int verticalSlotCount = Math.max(1, container.slotHeight);
        int slotPairWidth = 18 * (3 + gate.variant.numTriggerArgs + gate.variant.numActionArgs);
        int totalStatementWidth = slotPairWidth + (horizontalSlotCount > 1 ? slotPairWidth + 18 : 0);
        int slotPairStart = (162 - totalStatementWidth) / 2;
        int y = 16;
        int height = verticalSlotCount * 18;

        for (int column = 0; column < horizontalSlotCount; column++) {
            int x = slotPairStart + 7 + column * (18 + slotPairWidth);
            int triggerArgs = gate.variant.numTriggerArgs;
            int actionArgs = gate.variant.numActionArgs;
            // These rectangles mirror gate.json's 18px trigger / argument / connector / action cells.
            GuiHelpUtil.addRoot(mainGui, x, y, 18, height, "buildcraft.help.gate.trigger.title", 0xFF_FF_88_88,
                "buildcraft.help.gate.trigger.desc1", "buildcraft.help.gate.trigger.desc2");
            if (triggerArgs > 0) {
                GuiHelpUtil.addRoot(mainGui, x + 18, y, 18 * triggerArgs, height,
                    "buildcraft.help.gate.params.title", 0xFF_DD_AA_FF, "buildcraft.help.gate.params.desc");
            }
            int connectionX = x + 18 * (1 + triggerArgs);
            GuiHelpUtil.addRoot(mainGui, connectionX, y, 18, height, "buildcraft.help.gate.connection.title",
                0xFF_88_FF_AA, "buildcraft.help.gate.connection.desc");
            int actionX = connectionX + 18;
            GuiHelpUtil.addRoot(mainGui, actionX, y, 18, height, "buildcraft.help.gate.action.title", 0xFF_88_CC_FF,
                "buildcraft.help.gate.action.desc1", "buildcraft.help.gate.action.desc2");
            if (actionArgs > 0) {
                GuiHelpUtil.addRoot(mainGui, actionX + 18, y, 18 * actionArgs, height,
                    "buildcraft.help.gate.params.title", 0xFF_DD_AA_FF, "buildcraft.help.gate.params.desc");
            }
        }
    }

    protected void preLoad(BuildCraftJsonGui json) {
        GateLogic gate = container.gate;
        TypedKeyMap<String, Object> properties = json.properties;
        FunctionContext context = json.context;
        properties.put("statement.container", gate);
        context.putConstantBoolean("gate.two_columns", gate.isSplitInTwo());
        context.putConstantLong("gate.slots", gate.variant.numSlots);
        context.putConstantLong("gate.triggers.args", gate.variant.numTriggerArgs);
        context.putConstantLong("gate.actions.args", gate.variant.numActionArgs);
        context.put_b("gate.two_columns", () -> gate.isOn);
        context.putConstant("gate.material", String.class, gate.variant.material.tag);
        context.putConstant("gate.modifier", String.class, gate.variant.modifier.tag);
        context.putConstant("gate.logic", String.class, gate.variant.logic.tag);
        context.putConstant("gate.variant", String.class, gate.variant.getLocalizedName().getString());
        properties.put("gate.triggers.possible", container.possibleTriggersContext);
        properties.put("gate.actions.possible", container.possibleActionsContext);

        context.put_l_b("gate.is_connected", (i) -> {
            if (i < 0 || i >= gate.connections.length) {
                return false;
            }
            return gate.connections[(int) i];
        }).setNeverInline();

        context.put_l_b("gate.trigger.is_on", (i) -> {
            if (i < 0 || i >= gate.triggerOn.length) {
                return false;
            }
            return gate.triggerOn[(int) i];
        }).setNeverInline();

        context.put_l_b("gate.set.is_on", (i) -> {
            if (i < 0 || i >= gate.triggerOn.length) {
                return false;
            }
            return gate.actionOn[(int) i];
        }).setNeverInline();

        context.put_l_b("gate.action.is_on", (i) -> {
            if (i < 0 || i >= gate.actionOn.length) {
                return false;
            }
            return gate.actionOn[(int) i] && gate.statements[(int) i].action.get() != null;
        }).setNeverInline();

        for (int s = 0; s < gate.variant.numSlots; s++) {
            final int i = s;
            String tName = "gate.trigger/" + i;
            String aName = "gate.action/" + i;
            properties.put(tName, gate.statements[i].trigger);
            properties.put(aName, gate.statements[i].action);
            properties.put(tName, container.possibleTriggersContext);
            properties.put(aName, container.possibleActionsContext);
        }

        for (int c = 0; c < gate.connections.length; c++) {
            final int connection = c;
            String name = "gate.connection/" + c;
            properties.put(name, gate.connections[c]);
            properties.put(name, IButtonBehaviour.TOGGLE);
            properties.put(name, (IButtonClickEventListener) (b, k) -> {
                container.setConnected(connection, b.isButtonActive());
            });
        }
    }
}
