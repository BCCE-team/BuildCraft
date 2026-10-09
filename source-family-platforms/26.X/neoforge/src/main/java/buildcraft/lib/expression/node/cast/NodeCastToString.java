/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.expression.node.cast;

import buildcraft.lib.expression.NodeInliningHelper;
import buildcraft.lib.expression.api.IDependancyVisitor;
import buildcraft.lib.expression.api.IDependantNode;
import buildcraft.lib.expression.api.IExpressionNode;
import buildcraft.lib.expression.api.IExpressionNode.INodeObject;
import buildcraft.lib.expression.node.value.NodeConstantObject;

public class NodeCastToString implements INodeObject<String>, IDependantNode {
    private final IExpressionNode from;

    public NodeCastToString(IExpressionNode from) {
        this.from = from;
    }

    public Class<String> getType() {
        return String.class;
    }

    public String evaluate() {
        return from.evaluateAsString();
    }

    public INodeObject<String> inline() {
        return NodeInliningHelper.tryInline(this, from, NodeCastToString::new,
            (f) -> new NodeConstantObject<>(String.class, f.evaluateAsString()));
    }

    public void visitDependants(IDependancyVisitor visitor) {
        visitor.dependOn(from);
    }

    public String toString() {
        return "_to_string(" + from + ")";
    }
}
