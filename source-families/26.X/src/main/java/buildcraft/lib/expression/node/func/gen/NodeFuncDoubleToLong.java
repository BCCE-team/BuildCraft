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
import buildcraft.lib.expression.api.INodeFunc.INodeFuncLong;
import buildcraft.lib.expression.api.INodeStack;
import buildcraft.lib.expression.api.InvalidExpressionException;
import buildcraft.lib.expression.api.NodeTypes;
import buildcraft.lib.expression.node.func.StringFunctionBi;
import buildcraft.lib.expression.node.func.NodeFuncBase;
import buildcraft.lib.expression.node.func.NodeFuncBase.IFunctionNode;
import buildcraft.lib.expression.node.value.NodeConstantLong;

// AUTO_GENERATED FILE, DO NOT EDIT MANUALLY!
public class NodeFuncDoubleToLong extends NodeFuncBase implements INodeFuncLong {

    public final IFuncDoubleToLong function;
    private final StringFunctionBi stringFunction;

    public NodeFuncDoubleToLong(String name, IFuncDoubleToLong function) {
        this(function, (a) -> "[ double -> long ] " + name + "(" + a +  ")");
    }

    public NodeFuncDoubleToLong(IFuncDoubleToLong function, StringFunctionBi stringFunction) {

        this.function = function;
        this.stringFunction = stringFunction;
    }

    public String toString() {
        return stringFunction.apply("{A}");
    }

    public NodeFuncDoubleToLong setNeverInline() {
        super.setNeverInline();
        return this;
    }

    public INodeLong getNode(INodeStack stack) throws InvalidExpressionException {

        INodeDouble a = stack.popDouble();

        return create(a);
    }

    /** Shortcut to create a new {@link FuncDoubleToLong} without needing to create
     *  and populate an {@link INodeStack} to pass to {@link #getNode(INodeStack)}. */
    public FuncDoubleToLong create(INodeDouble argA) {
        return new FuncDoubleToLong(argA); 
    }

    public class FuncDoubleToLong implements INodeLong, IDependantNode, IFunctionNode {
        public final INodeDouble argA;

        public FuncDoubleToLong(INodeDouble argA) {
            this.argA = argA;

        }

        public long evaluate() {
            return function.apply(argA.evaluate());
        }

        public INodeLong inline() {
            if (!canInline) {
                // Note that we can still inline the arguments, just not *this* function
                return NodeInliningHelper.tryInline(this, argA,
                    (a) -> new FuncDoubleToLong(a),
                    (a) -> new FuncDoubleToLong(a)
                );
            }
            return NodeInliningHelper.tryInline(this, argA,
                (a) -> new FuncDoubleToLong(a),
                (a) -> NodeConstantLong.of(function.apply(a.evaluate()))
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
            return NodeFuncDoubleToLong.this;
        }

        public int hashCode() {
            return Objects.hash(argA);
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FuncDoubleToLong other = (FuncDoubleToLong) obj;
            return Objects.equals(argA, other.argA);
        }
    }

    @FunctionalInterface
    public interface IFuncDoubleToLong {
        long apply(double a);
    }
}
