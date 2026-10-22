/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.expression.node.value;

import java.util.Objects;

import buildcraft.lib.expression.api.IConstantNode;
import buildcraft.lib.expression.api.IExpressionNode.INodeObject;

public final class NodeConstantObject<T> implements INodeObject<T>, IConstantNode {
    public static final NodeConstantObject<String> EMPTY_STRING = new NodeConstantObject<>(String.class, "");

    public final Class<T> type;
    public final T value;

    public NodeConstantObject(Class<T> type, T value) {
        this.type = type;
        this.value = value;
    }

    public Class<T> getType() {
        return type;
    }

    public T evaluate() {
        return value;
    }

    public INodeObject<T> inline() {
        return this;
    }

    public String toString() {
        return value instanceof String ? ("'" + value + "'") : value.toString();
    }

    public int hashCode() {
        return Objects.hashCode(value);
    }

    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        NodeConstantObject<?> other = (NodeConstantObject<?>) obj;
        return getType() == other.getType() && Objects.equals(value, other.value);
    }
}
