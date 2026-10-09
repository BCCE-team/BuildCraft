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
import buildcraft.lib.expression.api.INodeFunc.INodeFuncObject;
import buildcraft.lib.expression.api.INodeStack;
import buildcraft.lib.expression.api.InvalidExpressionException;
import buildcraft.lib.expression.api.NodeTypes;
import buildcraft.lib.expression.node.func.StringFunctionBi;
import buildcraft.lib.expression.node.func.NodeFuncBase;
import buildcraft.lib.expression.node.func.NodeFuncBase.IFunctionNode;
import buildcraft.lib.expression.node.value.NodeConstantObject;

// AUTO_GENERATED FILE, DO NOT EDIT MANUALLY!
public class NodeFuncObjectToObject<A, R> extends NodeFuncBase implements INodeFuncObject<R> {

    public final IFuncObjectToObject<A, R> function;
    private final StringFunctionBi stringFunction;
    private final Class<A> argTypeA;
    private final Class<R> returnType;

    public NodeFuncObjectToObject(String name, Class<A> argTypeA, Class<R> returnType, IFuncObjectToObject<A, R> function) {
        this(argTypeA, returnType, function, (a) -> "[ " + NodeTypes.getName(argTypeA) + " -> " + NodeTypes.getName(returnType) + " ] " + name + "(" + a +  ")");
    }

    public NodeFuncObjectToObject(Class<A> argTypeA, Class<R> returnType, IFuncObjectToObject<A, R> function, StringFunctionBi stringFunction) {
        this.argTypeA = argTypeA;
        this.returnType = returnType;

        this.function = function;
        this.stringFunction = stringFunction;
    }

    public Class<R> getType() {
        return returnType;
    }

    public String toString() {
        return stringFunction.apply("{A}");
    }

    public NodeFuncObjectToObject<A, R> setNeverInline() {
        super.setNeverInline();
        return this;
    }

    public INodeObject<R> getNode(INodeStack stack) throws InvalidExpressionException {

        INodeObject<A> a = stack.popObject(argTypeA);

        return create(a);
    }

    /** Shortcut to create a new {@link FuncObjectToObject} without needing to create
     *  and populate an {@link INodeStack} to pass to {@link #getNode(INodeStack)}. */
    public FuncObjectToObject create(INodeObject<A> argA) {
        return new FuncObjectToObject(argA); 
    }

    public class FuncObjectToObject implements INodeObject<R>, IDependantNode, IFunctionNode {
        public final INodeObject<A> argA;

        public FuncObjectToObject(INodeObject<A> argA) {
            this.argA = argA;

        }

        public Class<R> getType() {
            return returnType;
        }

        public R evaluate() {
            return function.apply(argA.evaluate());
        }

        public INodeObject<R> inline() {
            if (!canInline) {
                // Note that we can still inline the arguments, just not *this* function
                return NodeInliningHelper.tryInline(this, argA,
                    (a) -> new FuncObjectToObject(a),
                    (a) -> new FuncObjectToObject(a)
                );
            }
            return NodeInliningHelper.tryInline(this, argA,
                (a) -> new FuncObjectToObject(a),
                (a) -> new NodeConstantObject<>(returnType, function.apply(a.evaluate()))
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
            return NodeFuncObjectToObject.this;
        }

        public int hashCode() {
            return Objects.hash(argA);
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FuncObjectToObject other = (FuncObjectToObject) obj;
            return Objects.equals(argA, other.argA);
        }
    }

    @FunctionalInterface
    public interface IFuncObjectToObject<A, R> {
        R apply(A a);
    }
}
