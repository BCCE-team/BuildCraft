/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.expression.api;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public interface IExpressionNode {
    default IExpressionNode inline() {
        return this;
    }

    String evaluateAsString();

    // common expression types

    @FunctionalInterface
    public interface INodeDouble extends IExpressionNode, DoubleSupplier {
        double evaluate();

        default INodeDouble inline() {
            return this;
        }

        default String evaluateAsString() {
            return Double.toString(evaluate());
        }

        /** @deprecated As {@link #evaluate()} gives a better description as to the cost. */
        default double getAsDouble() {
            return evaluate();
        }
    }

    @FunctionalInterface
    public interface INodeLong extends IExpressionNode, LongSupplier, IntSupplier {
        long evaluate();

        default INodeLong inline() {
            return this;
        }

        default String evaluateAsString() {
            return Long.toString(evaluate());
        }

        /** @deprecated As {@link #evaluate()} gives a better description as to the cost. */
        default long getAsLong() {
            return evaluate();
        }

        /** @deprecated As {@link #evaluate()} gives a better description as to the cost. */
        default int getAsInt() {
            return (int) evaluate();
        }
    }

    @FunctionalInterface
    public interface INodeBoolean extends IExpressionNode, BooleanSupplier {
        boolean evaluate();

        default INodeBoolean inline() {
            return this;
        }

        default String evaluateAsString() {
            return Boolean.toString(evaluate());
        }

        /** @deprecated As {@link #evaluate()} gives a better description as to the cost. */
        default boolean getAsBoolean() {
            return evaluate();
        }

    }

    public interface INodeObject<T> extends IExpressionNode, Supplier<T> {
        T evaluate();

        Class<T> getType();

        default INodeObject<T> inline() {
            return this;
        }

        default String evaluateAsString() {
            return evaluate().toString();
        }

        /** @deprecated As {@link #evaluate()} gives a better description as to the cost. */
        default T get() {
            return evaluate();
        }

        public static <T> INodeObject<T> create(Class<T> clazz, Supplier<T> supplier) {
            return new INodeObject<T>() {
                public T evaluate() {
                    return supplier.get();
                }

                public Class<T> getType() {
                    return clazz;
                }
            };
        }
    }

    /** Common object types (Provided as functional interfaces, these should NEVER be tested against with instanceof */
    @FunctionalInterface
    public interface INodeString extends INodeObject<String> {
        default Class<String> getType() {
            return String.class;
        }
    }
}
