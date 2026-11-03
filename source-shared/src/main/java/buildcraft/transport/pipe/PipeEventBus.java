/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.transport.internal.pipe.PipeEvent;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventPriority;

public class PipeEventBus {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("transport.pipe.event_bus");

    private static final Map<Class<?>, List<Handler>> allHandlers = new HashMap<>();

    private final List<LocalHandler> currentHandlers = new ArrayList<>();
    private final Map<Class<?>, Boolean> eventPresence = new HashMap<>();

    private static List<LocalHandler> getAndBindHandlers(Object obj) {
        Class<?> cls = obj instanceof Class ? (Class<?>) obj : obj.getClass();

        List<Handler> handlerList = getHandlers(cls);
        List<LocalHandler> list = new ArrayList<>();
        for (Handler handler : handlerList) {
            LocalHandler bound = handler.bindTo(obj);
            /* The handler will be null if a class was registered but the method was not static */
            if (bound != null) {
                list.add(bound);
            }
        }
        return list;
    }

    /**
     * Class#getDeclaredMethods resolves every method descriptor in the declaring class,
     * including client-only RenderType parameters. Pipe events are registered while
     * loading pluggables on a dedicated server, so reflective enumeration would try
     * to load Minecraft's absent client renderer before it could filter annotations.
     * Scan annotation metadata without resolving unrelated method signatures.
     */
    private static List<Handler> getHandlers(Class<?> cls) {
        List<Handler> cached = allHandlers.get(cls);
        if (cached != null) return cached;
        if (cls == Object.class) return Collections.emptyList();

        List<Handler> list = new ArrayList<>();
        Class<?> superCls = cls.getSuperclass();
        if (superCls != null) list.addAll(getHandlers(superCls));

        for (HandlerMethod declaration : annotatedMethods(cls)) {
            MethodType signature = MethodType.fromMethodDescriptorString(declaration.descriptor, cls.getClassLoader());
            if (signature.parameterCount() != 1 || !PipeEvent.class.isAssignableFrom(signature.parameterType(0))) {
                throw new IllegalStateException("Cannot annotate " + cls.getName() + "." + declaration.name
                    + declaration.descriptor + " with @PipeEventHandler: expected one PipeEvent parameter");
            }
            boolean isStatic = Modifier.isStatic(declaration.access);
            MethodHandle handle;
            try {
                handle = isStatic
                    ? MethodHandles.publicLookup().findStatic(cls, declaration.name, signature)
                    : MethodHandles.publicLookup().findVirtual(cls, declaration.name, signature);
            } catch (NoSuchMethodException | IllegalAccessException error) {
                throw new IllegalStateException("Cannot resolve @PipeEventHandler " + cls.getName() + "."
                    + declaration.name + declaration.descriptor + " as a public handler", error);
            }
            list.add(new Handler(declaration.priority, declaration.receiveCancelled, isStatic,
                cls.getName() + "." + declaration.name + declaration.descriptor, handle, signature.parameterType(0)));
        }
        allHandlers.put(cls, list);
        return list;
    }

    private static List<HandlerMethod> annotatedMethods(Class<?> cls) {
        List<HandlerMethod> declarations = new ArrayList<>();
        String resource = "/" + cls.getName().replace('.', '/') + ".class";
        try (InputStream input = cls.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Cannot inspect pipe event handlers: missing class bytes for " + cls.getName());
            }
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!Type.getDescriptor(PipeEventHandler.class).equals(annotation)) return null;
                            HandlerMethod handler = new HandlerMethod(access, name, descriptor);
                            declarations.add(handler);
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String key, Object value) {
                                    if ("receiveCancelled".equals(key)) handler.receiveCancelled = (Boolean) value;
                                }
                                @Override
                                public void visitEnum(String key, String type, String value) {
                                    if ("priority".equals(key)) handler.priority = PipeEventPriority.valueOf(value);
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to inspect @PipeEventHandler metadata for " + cls.getName(), error);
        }
        return declarations;
    }

    private static final class HandlerMethod {
        final int access;
        final String name;
        final String descriptor;
        PipeEventPriority priority = PipeEventPriority.NORMAL;
        boolean receiveCancelled;

        HandlerMethod(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    public void registerHandler(Object obj) {
        if (obj == null) {
            return;
        }
        currentHandlers.addAll(getAndBindHandlers(obj));
        Collections.sort(currentHandlers);
        eventPresence.clear();
    }

    public void unregisterHandler(Object obj) {
        if (obj == null) {
            return;
        }

        currentHandlers.removeIf(next -> next.target == obj);
        eventPresence.clear();
    }

    /** Sends this event to all of the registered handlers.
     * 
     * @return True if at least 1 event handler was called, 0 if no handlers were called. */
    public boolean fireEvent(PipeEvent event) {
        Class<?> eventClass = event.getClass();
        boolean present = eventPresence.computeIfAbsent(eventClass, cls -> {
            for (LocalHandler handler : currentHandlers) {
                if (handler.classHandled.isAssignableFrom(cls)) return true;
            }
            return false;
        });
        if (!present) return false;

        boolean handled = false;
        if (DEBUG) {
            String error = event.checkStateForErrors();
            if (error != null) {
                throw new IllegalArgumentException("The event " + event.getClass() + " was in an invalid state when firing! This is DEFINITELY a bug!\n"//
                    + "(error = " + error + ")");
            }
        }
        for (LocalHandler handler : currentHandlers) {
            handled |= handler.handleEvent(event);
            if (DEBUG) {
                String error = event.checkStateForErrors();
                if (error != null) {
                    throw new IllegalStateException("The event " + event.getClass() + " was in an invalid state after being handled by "//
                        + handler.methodName + " (error = " + error + ")");
                }
            }
        }
        return handled;
    }

    public static class Handler {
        final PipeEventPriority priority;
        final boolean receiveCanceled, isStatic;
        final String methodName;
        final MethodHandle handle;
        final Class<?> eventClassHandled;

        public Handler(PipeEventPriority priority, boolean receiveCanceled, boolean isStatic, String methodName, MethodHandle handle, Class<?> eventClassHandled) {
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
            this.isStatic = isStatic;
            this.methodName = methodName;
            this.handle = handle;
            this.eventClassHandled = eventClassHandled;
        }

        public LocalHandler bindTo(Object obj) {
            // If its not a static method then we cannot pass the class to the handler, so we won't bind it
            if (!isStatic && obj instanceof Class<?>) {
                return null;
            }
            MethodHandle bound = isStatic ? handle : handle.bindTo(obj);
            return new LocalHandler(priority, receiveCanceled, obj, methodName, eventClassHandled, bound);
        }
    }

    public static class LocalHandler implements Comparable<LocalHandler> {
        final PipeEventPriority priority;
        final boolean receiveCanceled;
        final Object target;
        final String methodName;
        final Class<?> classHandled;
        final MethodHandle handle;

        public LocalHandler(PipeEventPriority priority, boolean receiveCanceled, Object target, String methodName, Class<?> classHandled, MethodHandle handle) {
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
            this.target = target;
            this.methodName = methodName;
            this.classHandled = classHandled;
            this.handle = handle;
        }

        public boolean handleEvent(PipeEvent event) {
            if (!receiveCanceled && event.isCanceled()) {
                return false;
            }

            if (classHandled.isAssignableFrom(event.getClass())) {
                try {
                    handle.invoke(event);
                    return true;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }
            return false;
        }

        @Override
        public int compareTo(LocalHandler o) {
            return priority.compareTo(o.priority);
        }
    }
}
