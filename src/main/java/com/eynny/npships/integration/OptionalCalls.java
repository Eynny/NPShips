package com.eynny.npships.integration;

/** Optional integrations never link foreign classes from common class signatures. */
public final class OptionalCalls {
    private OptionalCalls() {}

    public static Object call(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (var method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                var types = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < types.length; i++)
                    if (args[i] != null
                            && !types[i].isInstance(args[i])
                            && !(types[i] == int.class && args[i] instanceof Integer)
                            && !(types[i] == boolean.class && args[i] instanceof Boolean))
                        compatible = false;
                if (compatible) {
                    // Enum constants and inventory handlers can have non-public implementations.
                    if (!java.lang.reflect.Modifier.isPublic(
                            method.getDeclaringClass().getModifiers())) {
                        var parent = type.getSuperclass();
                        while (parent != null) {
                            try {
                                var inherited = parent.getMethod(name, method.getParameterTypes());
                                if (java.lang.reflect.Modifier.isPublic(
                                        inherited.getDeclaringClass().getModifiers())) {
                                    method = inherited;
                                    break;
                                }
                            } catch (NoSuchMethodException ignored) {
                            }
                            parent = parent.getSuperclass();
                        }
                    }
                    return method.invoke(target instanceof Class<?> ? null : target, args);
                }
            }
        throw new NoSuchMethodException(
                type.getName() + "." + name + " (unsupported integration version)");
    }

    public static Object field(Object object, String name) throws ReflectiveOperationException {
        return object.getClass().getField(name).get(object);
    }
}
