package buildcraft.lib.expression.node.value;

import java.util.Locale;

import buildcraft.lib.expression.api.IExpressionNode;
import buildcraft.lib.expression.api.IVariableNode;

public abstract class NodeVariable implements IVariableNode {

    public final String name;
    protected boolean isConst = false;

    public NodeVariable(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public void setConstant(boolean isConst) {
        this.isConst = isConst;
    }

    public boolean isConstant() {
        return isConst;
    }

    public abstract void setConstantSource(IExpressionNode source);

    public String toString() {
        return "variable: " + name + " = " + evaluateAsString();
    }
}
