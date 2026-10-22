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
public class NodeFuncLongToLong extends NodeFuncBase implements INodeFuncLong {

    public final IFuncLongToLong function;
    private final StringFunctionBi stringFunction;

    public NodeFuncLongToLong(String name, IFuncLongToLong function) {
        this(function, (a) -> "[ long -> long ] " + name + "(" + a +  ")");
    }

    public NodeFuncLongToLong(IFuncLongToLong function, StringFunctionBi stringFunction) {

        this.function = function;
        this.stringFunction = stringFunction;
    }

    public String toString() {
        return stringFunction.apply("{A}");
    }

    public NodeFuncLongToLong setNeverInline() {
        super.setNeverInline();
        return this;
    }

    public INodeLong getNode(INodeStack stack) throws InvalidExpressionException {

        INodeLong a = stack.popLong();

        return create(a);
    }

    /** Shortcut to create a new {@link FuncLongToLong} without needing to create
     *  and populate an {@link INodeStack} to pass to {@link #getNode(INodeStack)}. */
    public FuncLongToLong create(INodeLong argA) {
        return new FuncLongToLong(argA); 
    }

    public class FuncLongToLong implements INodeLong, IDependantNode, IFunctionNode {
        public final INodeLong argA;

        public FuncLongToLong(INodeLong argA) {
            this.argA = argA;

        }

        public long evaluate() {
            return function.apply(argA.evaluate());
        }

        public INodeLong inline() {
            if (!canInline) {
                // Note that we can still inline the arguments, just not *this* function
                return NodeInliningHelper.tryInline(this, argA,
                    (a) -> new FuncLongToLong(a),
                    (a) -> new FuncLongToLong(a)
                );
            }
            return NodeInliningHelper.tryInline(this, argA,
                (a) -> new FuncLongToLong(a),
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
            return NodeFuncLongToLong.this;
        }

        public int hashCode() {
            return Objects.hash(argA);
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FuncLongToLong other = (FuncLongToLong) obj;
            return Objects.equals(argA, other.argA);
        }
    }

    @FunctionalInterface
    public interface IFuncLongToLong {
        long apply(long a);
    }
}
