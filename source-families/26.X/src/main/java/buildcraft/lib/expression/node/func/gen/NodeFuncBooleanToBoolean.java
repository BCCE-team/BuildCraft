/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.expression.node.func.gen;

import java.util.Objects;

import buildcraft.lib.expression.NodeInliningHelper;
import buildcraft.lib.expression.api.IDependantNode;
import buildcraft.lib.expression.api.IDependancyVisitor;
import buildcraft.lib.expression.api.IExpressionNode.INodeBoolean;
import buildcraft.lib.expression.api.IExpressionNode.INodeDouble;
import buildcraft.lib.expression.api.IExpressionNode.INodeLong;
import buildcraft.lib.expression.api.IExpressionNode.INodeObject;
import buildcraft.lib.expression.api.INodeFunc.INodeFuncBoolean;
import buildcraft.lib.expression.api.INodeStack;
import buildcraft.lib.expression.api.InvalidExpressionException;
import buildcraft.lib.expression.api.NodeTypes;
import buildcraft.lib.expression.node.func.StringFunctionBi;
import buildcraft.lib.expression.node.func.NodeFuncBase;
import buildcraft.lib.expression.node.func.NodeFuncBase.IFunctionNode;
import buildcraft.lib.expression.node.value.NodeConstantBoolean;

// AUTO_GENERATED FILE, DO NOT EDIT MANUALLY!
public class NodeFuncBooleanToBoolean extends NodeFuncBase implements INodeFuncBoolean {

    public final IFuncBooleanToBoolean function;
    private final StringFunctionBi stringFunction;

    public NodeFuncBooleanToBoolean(String name, IFuncBooleanToBoolean function) {
        this(function, (a) -> "[ boolean -> boolean ] " + name + "(" + a +  ")");
    }

    public NodeFuncBooleanToBoolean(IFuncBooleanToBoolean function, StringFunctionBi stringFunction) {

        this.function = function;
        this.stringFunction = stringFunction;
    }

    public String toString() {
        return stringFunction.apply("{A}");
    }

    public NodeFuncBooleanToBoolean setNeverInline() {
        super.setNeverInline();
        return this;
    }

    public INodeBoolean getNode(INodeStack stack) throws InvalidExpressionException {

        INodeBoolean a = stack.popBoolean();

        return create(a);
    }

    /** Shortcut to create a new {@link FuncBooleanToBoolean} without needing to create
     *  and populate an {@link INodeStack} to pass to {@link #getNode(INodeStack)}. */
    public FuncBooleanToBoolean create(INodeBoolean argA) {
        return new FuncBooleanToBoolean(argA); 
    }

    public class FuncBooleanToBoolean implements INodeBoolean, IDependantNode, IFunctionNode {
        public final INodeBoolean argA;

        public FuncBooleanToBoolean(INodeBoolean argA) {
            this.argA = argA;

        }

        public boolean evaluate() {
            return function.apply(argA.evaluate());
        }

        public INodeBoolean inline() {
            if (!canInline) {
                // Note that we can still inline the arguments, just not *this* function
                return NodeInliningHelper.tryInline(this, argA,
                    (a) -> new FuncBooleanToBoolean(a),
                    (a) -> new FuncBooleanToBoolean(a)
                );
            }
            return NodeInliningHelper.tryInline(this, argA,
                (a) -> new FuncBooleanToBoolean(a),
                (a) -> NodeConstantBoolean.of(function.apply(a.evaluate()))
            );
        }

        public void visitDependants(IDependancyVisitor visitor) {
            if (!canInline) {
                if (function instanceof IDependantNode) {
                    visitor.dependOn((IDependantNode) function);
                } else {
                    visitor.dependOnExplictly(this);
                }
            }
            visitor.dependOn(argA);
        }

        public String toString() {
            return stringFunction.apply(argA.toString());
        }

        public NodeFuncBase getFunction() {
            return NodeFuncBooleanToBoolean.this;
        }

        public int hashCode() {
            return Objects.hash(argA);
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FuncBooleanToBoolean other = (FuncBooleanToBoolean) obj;
            return Objects.equals(argA, other.argA);
        }
    }

    @FunctionalInterface
    public interface IFuncBooleanToBoolean {
        boolean apply(boolean a);
    }
}
