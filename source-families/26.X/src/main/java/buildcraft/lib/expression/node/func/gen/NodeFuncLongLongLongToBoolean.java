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
import buildcraft.lib.expression.node.func.StringFunctionQuad;
import buildcraft.lib.expression.node.func.NodeFuncBase;
import buildcraft.lib.expression.node.func.NodeFuncBase.IFunctionNode;
import buildcraft.lib.expression.node.value.NodeConstantBoolean;

// AUTO_GENERATED FILE, DO NOT EDIT MANUALLY!
public class NodeFuncLongLongLongToBoolean extends NodeFuncBase implements INodeFuncBoolean {

    public final IFuncLongLongLongToBoolean function;
    private final StringFunctionQuad stringFunction;

    public NodeFuncLongLongLongToBoolean(String name, IFuncLongLongLongToBoolean function) {
        this(function, (a, b, c) -> "[ long, long, long -> boolean ] " + name + "(" + a + ", " + b + ", " + c +  ")");
    }

    public NodeFuncLongLongLongToBoolean(IFuncLongLongLongToBoolean function, StringFunctionQuad stringFunction) {

        this.function = function;
        this.stringFunction = stringFunction;
    }

    public String toString() {
        return stringFunction.apply("{A}", "{B}", "{C}");
    }

    public NodeFuncLongLongLongToBoolean setNeverInline() {
        super.setNeverInline();
        return this;
    }

    public INodeBoolean getNode(INodeStack stack) throws InvalidExpressionException {

        INodeLong c = stack.popLong();
        INodeLong b = stack.popLong();
        INodeLong a = stack.popLong();

        return create(a, b, c);
    }

    /** Shortcut to create a new {@link FuncLongLongLongToBoolean} without needing to create
     *  and populate an {@link INodeStack} to pass to {@link #getNode(INodeStack)}. */
    public FuncLongLongLongToBoolean create(INodeLong argA, INodeLong argB, INodeLong argC) {
        return new FuncLongLongLongToBoolean(argA, argB, argC); 
    }

    public class FuncLongLongLongToBoolean implements INodeBoolean, IDependantNode, IFunctionNode {
        public final INodeLong argA;
        public final INodeLong argB;
        public final INodeLong argC;

        public FuncLongLongLongToBoolean(INodeLong argA, INodeLong argB, INodeLong argC) {
            this.argA = argA;
            this.argB = argB;
            this.argC = argC;

        }

        public boolean evaluate() {
            return function.apply(argA.evaluate(), argB.evaluate(), argC.evaluate());
        }

        public INodeBoolean inline() {
            if (!canInline) {
                // Note that we can still inline the arguments, just not *this* function
                return NodeInliningHelper.tryInline(this, argA, argB, argC,
                    (a, b, c) -> new FuncLongLongLongToBoolean(a, b, c),
                    (a, b, c) -> new FuncLongLongLongToBoolean(a, b, c)
                );
            }
            return NodeInliningHelper.tryInline(this, argA, argB, argC,
                (a, b, c) -> new FuncLongLongLongToBoolean(a, b, c),
                (a, b, c) -> NodeConstantBoolean.of(function.apply(a.evaluate(), b.evaluate(), c.evaluate()))
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
            visitor.dependOn(argA, argB, argC);
        }

        public String toString() {
            return stringFunction.apply(argA.toString(), argB.toString(), argC.toString());
        }

        public NodeFuncBase getFunction() {
            return NodeFuncLongLongLongToBoolean.this;
        }

        public int hashCode() {
            return Objects.hash(argA, argB, argC);
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FuncLongLongLongToBoolean other = (FuncLongLongLongToBoolean) obj;
            return Objects.equals(argA, other.argA) //
            &&Objects.equals(argB, other.argB) //
            &&Objects.equals(argC, other.argC);
        }
    }

    @FunctionalInterface
    public interface IFuncLongLongLongToBoolean {
        boolean apply(long a, long b, long c);
    }
}
