package buildcraft.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Runtime GameTest compatibility across modern Minecraft API shapes. */
public final class GameTestCompat {
    private GameTestCompat() {
    }

    public static BlockEntity getBlockEntity(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos));
    }

    public static int readInt(CompoundTag tag, String key) {
        Object value = invoke(findMethod(CompoundTag.class, "getInt", 1), tag, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Optional<?> optional && optional.orElse(null) instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    public static String readString(CompoundTag tag, String key) {
        Object value = invoke(findMethod(CompoundTag.class, "getString", 1), tag, key);
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Optional<?> optional && optional.orElse(null) instanceof String string) {
            return string;
        }
        return "";
    }

    public static UUID profileId(GameProfile profile) {
        Object value = invokeFirst(profile, "id", "getId");
        return value instanceof UUID uuid ? uuid : null;
    }

    public static String profileName(GameProfile profile) {
        Object value = invokeFirst(profile, "name", "getName");
        return value instanceof String name ? name : null;
    }

    public static CompoundTag saveEntity(Entity entity) {
        Method legacy = findCompatibleMethod(entity.getClass(), "saveWithoutId", CompoundTag.class);
        if (legacy != null) {
            CompoundTag tag = new CompoundTag();
            invoke(legacy, entity, tag);
            return tag;
        }
        try {
            Class<?> valueOutput = Class.forName("net.minecraft.world.level.storage.ValueOutput");
            Method save = entity.getClass().getMethod("saveWithoutId", valueOutput);
            Class<?> reporterClass = Class.forName("net.minecraft.util.ProblemReporter");
            Object reporter = reporterClass.getField("DISCARDING").get(null);
            Object registries = invoke(findMethod(entity.getClass(), "registryAccess", 0), entity);
            Class<?> tagOutputClass = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
            Method create = findStaticCompatibleMethod(tagOutputClass, "createWithContext", reporter, registries);
            Object output = invoke(create, null, reporter, registries);
            invoke(save, entity, output);
            return (CompoundTag) invoke(findMethod(tagOutputClass, "buildResult", 0), output);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot serialize GameTest entity", exception);
        }
    }

    public static void loadEntity(Entity entity, CompoundTag tag) {
        Method legacy = findCompatibleMethod(entity.getClass(), "load", CompoundTag.class);
        if (legacy != null) {
            invoke(legacy, entity, tag);
            return;
        }
        try {
            Class<?> valueInput = Class.forName("net.minecraft.world.level.storage.ValueInput");
            Method load = entity.getClass().getMethod("load", valueInput);
            Class<?> reporterClass = Class.forName("net.minecraft.util.ProblemReporter");
            Object reporter = reporterClass.getField("DISCARDING").get(null);
            Object registries = invoke(findMethod(entity.getClass(), "registryAccess", 0), entity);
            Class<?> tagInputClass = Class.forName("net.minecraft.world.level.storage.TagValueInput");
            Method create = findStaticCompatibleMethod(tagInputClass, "create", reporter, registries, tag);
            Object input = invoke(create, null, reporter, registries, tag);
            invoke(load, entity, input);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot deserialize GameTest entity", exception);
        }
    }

    public static CompoundTag saveBlockEntity(BlockEntity blockEntity, GameTestHelper helper) {
        Object registries = helper.getLevel().registryAccess();
        Method legacy = findDeclaredCompatibleMethod(
            blockEntity.getClass(), "saveAdditional", CompoundTag.class, registries.getClass()
        );
        if (legacy != null) {
            CompoundTag tag = new CompoundTag();
            invokeAccessible(legacy, blockEntity, tag, registries);
            return tag;
        }

        try {
            Class<?> valueOutput = Class.forName("net.minecraft.world.level.storage.ValueOutput");
            Method save = findDeclaredCompatibleMethod(blockEntity.getClass(), "saveAdditional", valueOutput);
            Class<?> reporterClass = Class.forName("net.minecraft.util.ProblemReporter");
            Object reporter = reporterClass.getField("DISCARDING").get(null);
            Class<?> tagOutputClass = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
            Method create = findStaticCompatibleMethod(tagOutputClass, "createWithContext", reporter, registries);
            Object output = invoke(create, null, reporter, registries);
            invokeAccessible(save, blockEntity, output);
            return (CompoundTag) invoke(findMethod(tagOutputClass, "buildResult", 0), output);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot serialize GameTest block entity", exception);
        }
    }

    public static void loadBlockEntity(BlockEntity blockEntity, CompoundTag tag, GameTestHelper helper) {
        Object registries = helper.getLevel().registryAccess();
        Method legacy = findDeclaredCompatibleMethod(
            blockEntity.getClass(), "loadAdditional", CompoundTag.class, registries.getClass()
        );
        if (legacy != null) {
            invokeAccessible(legacy, blockEntity, tag.copy(), registries);
            return;
        }

        try {
            Class<?> valueInput = Class.forName("net.minecraft.world.level.storage.ValueInput");
            Method load = findDeclaredCompatibleMethod(blockEntity.getClass(), "loadAdditional", valueInput);
            Class<?> reporterClass = Class.forName("net.minecraft.util.ProblemReporter");
            Object reporter = reporterClass.getField("DISCARDING").get(null);
            Class<?> tagInputClass = Class.forName("net.minecraft.world.level.storage.TagValueInput");
            Method create = findStaticCompatibleMethod(tagInputClass, "create", reporter, registries, tag);
            Object input = invoke(create, null, reporter, registries, tag.copy());
            invokeAccessible(load, blockEntity, input);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot deserialize GameTest block entity", exception);
        }
    }

    public static RecipeHolder<?> recipeById(GameTestHelper helper, ResourceLocation id) {
        Object level = helper.getLevel();
        Method direct = findMethod(level.getClass(), "getRecipeManager", 0);
        Object manager;
        if (direct != null) {
            manager = invoke(direct, level);
        } else {
            Object server = invoke(findMethod(level.getClass(), "getServer", 0), level);
            manager = invoke(findMethod(server.getClass(), "getRecipeManager", 0), server);
        }
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("byKey") || method.getParameterCount() != 1) {
                continue;
            }
            Object key = id;
            if (!method.getParameterTypes()[0].isInstance(id)) {
                key = recipeKey(id);
            }
            Object result = invoke(method, manager, key);
            if (result instanceof Optional<?> optional) {
                Object holder = optional.orElse(null);
                return holder instanceof RecipeHolder<?> recipeHolder ? recipeHolder : null;
            }
        }
        return null;
    }

    public static boolean recipeProduces(GameTestHelper helper, RecipeHolder<?> recipe, Item item) {
        if (recipe == null) {
            return false;
        }
        Object value = recipe.value();
        Method oldResult = findMethod(value.getClass(), "getResultItem", 1);
        if (oldResult != null) {
            Object registries = invoke(findMethod(helper.getLevel().getClass(), "registryAccess", 0), helper.getLevel());
            Object stack = invoke(oldResult, value, registries);
            return stack instanceof ItemStack itemStack && itemStack.getItem() == item;
        }
        Object displays = invoke(findMethod(value.getClass(), "display", 0), value);
        if (!(displays instanceof Iterable<?> iterable)) {
            return false;
        }
        Object context = slotDisplayContext(helper);
        for (Object display : iterable) {
            Object result = invoke(findMethod(display.getClass(), "result", 0), display);
            Object stack = invoke(findCompatibleMethod(result.getClass(), "resolveForFirstStack", context.getClass()), result, context);
            if (stack instanceof ItemStack itemStack && !itemStack.isEmpty() && itemStack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private static Object recipeKey(ResourceLocation id) {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
            Object recipeRegistry = registries.getField("RECIPE").get(null);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
            Method create = findStaticCompatibleMethod(resourceKey, "create", recipeRegistry, id);
            return invoke(create, null, recipeRegistry, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create GameTest recipe key", exception);
        }
    }

    private static Object slotDisplayContext(GameTestHelper helper) {
        try {
            Class<?> contextClass = Class.forName("net.minecraft.world.item.crafting.display.SlotDisplayContext");
            Method fromLevel = findStaticCompatibleMethod(contextClass, "fromLevel", helper.getLevel());
            return invoke(fromLevel, null, helper.getLevel());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Missing recipe display context", exception);
        }
    }

    private static Object invokeFirst(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Method method = findMethod(target.getClass(), name, 0);
            if (method != null) {
                return invoke(method, target);
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> parameterType) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isAssignableFrom(parameterType)) {
                return method;
            }
        }
        return null;
    }

    private static Method findDeclaredCompatibleMethod(Class<?> start, String name, Class<?>... argumentTypes) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != argumentTypes.length) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (!parameters[i].isAssignableFrom(argumentTypes[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findStaticCompatibleMethod(Class<?> type, String name, Object... arguments) {
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals(name)
                || method.getParameterCount() != arguments.length) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (arguments[i] != null && !parameters[i].isInstance(arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeAccessible(Method method, Object target, Object... arguments) {
        if (method == null) {
            throw new IllegalStateException("Required GameTest compatibility method is unavailable");
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access GameTest compatibility method " + method.getName(), exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("GameTest compatibility method failed: " + method.getName(), cause);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) {
            throw new IllegalStateException("Required GameTest compatibility method is unavailable");
        }
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access GameTest compatibility method " + method.getName(), exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("GameTest compatibility method failed: " + method.getName(), cause);
        }
    }
}
