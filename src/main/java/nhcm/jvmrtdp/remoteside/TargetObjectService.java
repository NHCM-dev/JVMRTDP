package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Target-side object, class, field, constructor and virtual-method operations. */
public class TargetObjectService implements AutoCloseable {
    private final TargetObjectRegistry objects = new TargetObjectRegistry();
    private final TargetSearchService search = new TargetSearchService();

    /** Stores a value produced by another target-side service in the session object registry. */
    public RemoteObjectDescriptor storeExternal(Object value) {
        return objects.store(value);
    }

    /** Stores callback/debugger values without running application-defined toString methods. */
    public RemoteObjectDescriptor storeExternalOpaque(Object value) {
        return objects.storeOpaque(value);
    }

    /** Resolves an argument handle for another target-side service. */
    public Object resolveExternal(long objectId) {
        return objects.resolve(objectId);
    }

    public Class<?> findClass(String className) {
        return NativeAgent.findLoadedClass(className);
    }

    /** Complete JVMTI loaded-class name snapshot without the search command's display limit. */
    public List<String> loadedClassNames() {
        List<String> result = new ArrayList<String>();
        for (String name : NativeAgent.listLoadedClassNames()) {
            if (name != null) result.add(name);
        }
        return result;
    }

    /** Reads a regular Java system property in the target VM's application runtime. */
    public String systemProperty(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("System property name must not be empty");
        }
        String value = System.getProperty(name);
        return value == null ? "" : value;
    }

    /** Loads and initializes a class in the target JVM, explicitly invoking Class.forName. */
    public Class<?> forceLoadClass(String className) {
        return resolveClassForName(className, true);
    }

    /** Loads and links a class in the target JVM without running its class initializer. */
    public Class<?> loadClassWithoutInitialization(String className) {
        return resolveClassForName(className, false);
    }

    /**
     * Loads/links first, then initializes on a non-service thread so a &lt;clinit&gt;
     * breakpoint may pause without blocking the command channel needed to resume it.
     */
    public Class<?> startForceLoadClass(String className) {
        final Class<?> prepared = resolveClassForName(className, false);
        final ClassLoader loader = prepared.getClassLoader();
        Thread initializer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Class.forName(prepared.getName(), true, loader);
                } catch (ClassNotFoundException impossible) {
                    throw new IllegalStateException("Prepared class disappeared: " + prepared.getName(), impossible);
                }
            }
        }, "Class.forName-" + prepared.getName());
        initializer.setContextClassLoader(loader);
        initializer.setDaemon(true);
        initializer.start();
        return prepared;
    }

    private Class<?> resolveClassForName(String className, boolean initialize) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name must not be empty");
        }
        String normalized = className.trim().replace('/', '.');
        List<ClassLoader> loaders = new ArrayList<ClassLoader>();
        addLoader(loaders, Thread.currentThread().getContextClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        addLoader(loaders, TargetObjectService.class.getClassLoader());
        ClassNotFoundException missing = null;
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(normalized, initialize, loader);
            } catch (ClassNotFoundException failure) {
                missing = failure;
            }
        }
        // Custom application loaders are common in IDEs, plugin systems and obfuscated tools.
        // Try each already-known loader by identity only after the conventional loaders fail.
        for (Class<?> loaded : NativeAgent.listLoadedClasses()) {
            ClassLoader loader = loaded == null ? null : loaded.getClassLoader();
            if (containsLoader(loaders, loader)) continue;
            loaders.add(loader);
            try {
                return Class.forName(normalized, initialize, loader);
            } catch (ClassNotFoundException failure) {
                missing = failure;
            }
        }
        throw new IllegalArgumentException("Class.forName could not load " + normalized
                + " with any known target ClassLoader", missing);
    }

    private static void addLoader(List<ClassLoader> loaders, ClassLoader loader) {
        if (!containsLoader(loaders, loader)) loaders.add(loader);
    }

    private static boolean containsLoader(List<ClassLoader> loaders, ClassLoader wanted) {
        for (ClassLoader loader : loaders) if (loader == wanted) return true;
        return false;
    }

    public RemoteObjectDescriptor value(String kind, String encodedValue) {
        String value = new String(Base64.getUrlDecoder().decode(encodedValue), StandardCharsets.UTF_8);
        if ("null".equals(kind)) return objects.store(null);
        if ("string".equals(kind)) return objects.store(value);
        if ("boolean".equals(kind)) return objects.store(Boolean.valueOf(value));
        if ("byte".equals(kind)) return objects.store(Byte.valueOf(value));
        if ("short".equals(kind)) return objects.store(Short.valueOf(value));
        if ("int".equals(kind)) return objects.store(Integer.valueOf(value));
        if ("long".equals(kind)) return objects.store(Long.valueOf(value));
        if ("float".equals(kind)) return objects.store(Float.valueOf(value));
        if ("double".equals(kind)) return objects.store(Double.valueOf(value));
        if ("char".equals(kind)) {
            if (value.length() != 1) throw new IllegalArgumentException("char requires exactly one character");
            return objects.store(Character.valueOf(value.charAt(0)));
        }
        if ("bytes".equals(kind)) return objects.store(Base64.getDecoder().decode(value));
        if ("class".equals(kind)) return objects.store(findClass(value));
        if (kind.startsWith("enum:")) {
            Class<?> enumClass = findClass(kind.substring("enum:".length()));
            if (!enumClass.isEnum()) throw new IllegalArgumentException(enumClass.getName() + " is not an enum");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumValue = Enum.valueOf((Class) enumClass, value);
            return objects.store(enumValue);
        }
        throw new IllegalArgumentException("Unsupported remote value kind: " + kind);
    }

    public RemoteObjectDescriptor construct(String className, String descriptor, long[] argumentIds) {
        Class<?> type = findClass(className);
        Object[] arguments = resolve(argumentIds);
        Constructor<?> constructor = "auto".equalsIgnoreCase(descriptor)
                ? selectConstructor(type, arguments)
                : findConstructor(type, descriptor);
        makeAccessible(constructor);
        try {
            return objects.store(constructor.newInstance(arguments));
        } catch (InvocationTargetException exception) {
            throw propagate("Constructor threw", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct " + className + descriptor, exception);
        }
    }

    public List<String> methods(String className, boolean staticMethods) {
        Class<?> type = findClass(className);
        Map<String, Method> methods = new LinkedHashMap<String, Method>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) == staticMethods) {
                    String key = method.getDeclaringClass().getName() + "#"
                            + method.getName() + JvmDescriptors.of(method);
                    if (!methods.containsKey(key)) methods.put(key, method);
                }
            }
        }
        List<String> result = new ArrayList<String>();
        for (Method method : methods.values()) {
            result.add(TextWireCodec.encode(
                    method.getDeclaringClass().getName(),
                    method.getName(),
                    JvmDescriptors.of(method),
                    Integer.toString(method.getModifiers()),
                    Boolean.toString(Modifier.isStatic(method.getModifiers()))));
        }
        return result;
    }

    public List<String> fields(String className, boolean staticFields) {
        Class<?> type = findClass(className);
        List<String> result = new ArrayList<String>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) == staticFields) {
                    result.add(TextWireCodec.encode(
                            field.getDeclaringClass().getName(),
                            field.getName(),
                            JvmDescriptors.of(field.getType()),
                            Integer.toString(field.getModifiers()),
                            Boolean.toString(Modifier.isStatic(field.getModifiers()))));
                }
            }
        }
        return result;
    }

    public List<String> constructors(String className) {
        List<String> result = new ArrayList<String>();
        for (Constructor<?> constructor : findClass(className).getDeclaredConstructors()) {
            result.add(TextWireCodec.encode(
                    constructor.getDeclaringClass().getName(),
                    JvmDescriptors.of(constructor),
                    Integer.toString(constructor.getModifiers())));
        }
        return result;
    }

    public String classInfo(String className) {
        Class<?> type = findClass(className);
        Class<?> superclass = type.getSuperclass();
        StringBuilder interfaces = new StringBuilder();
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (interfaces.length() != 0) interfaces.append(',');
            interfaces.append(interfaceType.getName());
        }
        return TextWireCodec.encode(
                type.getName(), Integer.toString(type.getModifiers()),
                superclass == null ? "" : superclass.getName(), interfaces.toString(),
                Boolean.toString(type.isInterface()), Boolean.toString(type.isEnum()),
                Boolean.toString(type.isArray()));
    }

    /** Lists the classes and immediate child packages currently loaded below a package. */
    public List<String> packageContents(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim().replace('/', '.');
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        String prefix = normalized.isEmpty() ? "" : normalized + ".";
        Set<String> packages = new TreeSet<String>();
        Set<String> classes = new TreeSet<String>();
        for (String className : NativeAgent.listLoadedClassNames()) {
            if (className == null) continue;
            if (className.startsWith("[") || !className.startsWith(prefix)) continue;
            String remainder = className.substring(prefix.length());
            if (remainder.isEmpty()) continue;
            int separator = remainder.indexOf('.');
            if (separator >= 0) {
                packages.add(prefix + remainder.substring(0, separator));
            } else {
                classes.add(className);
            }
        }
        List<String> result = new ArrayList<String>(packages.size() + classes.size());
        for (String child : packages) result.add(TextWireCodec.encode("package", child));
        for (String child : classes) result.add(TextWireCodec.encode("class", child));
        return result;
    }

    public List<String> searchClasses(
            String nameGlob, String kind, String packageGlob, String extendsGlob,
            String implementsGlob, int limit) {
        return search.classes(nameGlob, kind, packageGlob, extendsGlob, implementsGlob, limit);
    }

    public List<String> searchPackages(String glob, int limit) {
        return search.packages(glob, limit);
    }

    public List<String> searchFields(
            String classGlob, String nameGlob, String typeGlob, String mode, int limit) {
        return search.fields(classGlob, nameGlob, typeGlob, mode, limit);
    }

    public List<String> searchMethods(
            String classGlob, String nameGlob, String returnGlob,
            String parametersGlob, String mode, int limit) {
        return search.methods(classGlob, nameGlob, returnGlob, parametersGlob, mode, limit);
    }

    public int arrayLength(long objectId) {
        Object value = requireArray(objectId);
        return Array.getLength(value);
    }

    public RemoteObjectDescriptor arrayGet(long objectId, int index) {
        Object array = requireArray(objectId);
        checkIndex(array, index);
        return objects.store(Array.get(array, index), array.getClass().getComponentType().getName());
    }

    public void arraySet(long objectId, int index, long valueId) {
        Object array = requireArray(objectId);
        checkIndex(array, index);
        Array.set(array, index, objects.resolve(valueId));
    }

    public List<String> iterableElements(long objectId, int requestedLimit) {
        Object value = objects.resolve(objectId);
        if (!(value instanceof Iterable<?>)) {
            throw new IllegalArgumentException(className(objectId) + " is not Iterable");
        }
        int limit = checkedLimit(requestedLimit);
        List<String> result = new ArrayList<String>();
        Iterator<?> iterator = ((Iterable<?>) value).iterator();
        while (iterator.hasNext() && result.size() < limit) {
            result.add(objects.store(iterator.next()).encode());
        }
        return result;
    }

    public List<String> mapEntries(long objectId, int requestedLimit) {
        Object value = objects.resolve(objectId);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(className(objectId) + " is not a Map");
        }
        int limit = checkedLimit(requestedLimit);
        List<String> result = new ArrayList<String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (result.size() >= limit) break;
            RemoteObjectDescriptor key = objects.store(entry.getKey());
            RemoteObjectDescriptor item = objects.store(entry.getValue());
            result.add(TextWireCodec.encode(key.encode(), item.encode()));
        }
        return result;
    }

    public String statistics() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return TextWireCodec.encode(
                Integer.toString(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()),
                Long.toString(ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount()),
                Integer.toString(ManagementFactory.getThreadMXBean().getThreadCount()),
                Integer.toString(objects.size()),
                Long.toString(heap.getUsed()),
                Long.toString(heap.getMax()),
                Long.toString(ManagementFactory.getRuntimeMXBean().getUptime()),
                Integer.toString(runtime.availableProcessors()));
    }

    public String debug(long objectId) {
        Object value = objects.resolve(objectId);
        Class<?> type = value == null ? null : value.getClass();
        String shape = value == null ? "null"
                : type.isArray() ? "array"
                : value instanceof Map<?, ?> ? "map"
                : value instanceof Iterable<?> ? "iterable"
                : isPrimitiveLike(type) ? "primitive"
                : "object";
        String size = "";
        if (type != null && type.isArray()) size = Integer.toString(Array.getLength(value));
        else if (value instanceof Collection<?>) size = Integer.toString(((Collection<?>) value).size());
        else if (value instanceof Map<?, ?>) size = Integer.toString(((Map<?, ?>) value).size());
        return TextWireCodec.encode(
                Long.toString(objectId),
                type == null ? Object.class.getName() : type.getName(),
                shape,
                size,
                value == null ? "0" : Integer.toHexString(System.identityHashCode(value)),
                type == null ? "0" : Integer.toString(type.getDeclaredFields().length),
                type == null ? "0" : Integer.toString(type.getDeclaredMethods().length),
                objects.describe(objectId).displayValue());
    }

    public RemoteObjectDescriptor call(
            String declaringClass, String name, String descriptor, long receiverId, long[] argumentIds) {
        Method method = findMethod(findClass(declaringClass), name, descriptor);
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        Object receiver = isStatic ? null : objects.resolve(receiverId);
        if (!isStatic && receiver == null) {
            throw new NullPointerException("Virtual method receiver is null");
        }
        requireReceiver(method.getDeclaringClass(), receiver, "method", method.getName());
        makeAccessible(method);
        Object[] arguments = resolve(argumentIds);
        try {
            Object result = method.invoke(receiver, arguments);
            return objects.store(result, method.getReturnType().getName());
        } catch (InvocationTargetException exception) {
            throw propagate("Method threw", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot invoke " + declaringClass + "." + name + descriptor, exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Arguments " + argumentTypes(arguments)
                    + " are not compatible with " + method.getDeclaringClass().getName()
                    + "." + name + descriptor, exception);
        }
    }

    /** Invokes the selected declaring-class implementation without virtual override dispatch. */
    public RemoteObjectDescriptor callSpecial(
            String declaringClass, String name, String descriptor, long receiverId, long[] argumentIds) {
        Method method = findMethod(findClass(declaringClass), name, descriptor);
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Special invocation requires an instance method: " + name);
        }
        Object receiver = objects.resolve(receiverId);
        if (receiver == null) throw new NullPointerException("Special method receiver is null");
        requireReceiver(method.getDeclaringClass(), receiver, "method", method.getName());
        makeAccessible(method);
        try {
            MethodHandles.Lookup lookup = trustedLookup(method.getDeclaringClass());
            MethodHandle handle = lookup.unreflectSpecial(method, method.getDeclaringClass()).bindTo(receiver);
            Object result = handle.invokeWithArguments(resolve(argumentIds));
            return objects.store(result, method.getReturnType().getName());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw propagate("Cannot invoke parent implementation "
                    + method.getDeclaringClass().getName() + "." + name + descriptor, failure);
        }
    }

    public RemoteObjectDescriptor readField(
            String declaringClass, String name, String descriptor, long receiverId) {
        Field field = findField(findClass(declaringClass), name, descriptor);
        Object receiver = Modifier.isStatic(field.getModifiers()) ? null : objects.resolve(receiverId);
        requireReceiver(field.getDeclaringClass(), receiver, "field", field.getName());
        makeAccessible(field);
        try {
            return objects.store(field.get(receiver), field.getType().getName());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read field " + declaringClass + "." + name, exception);
        }
    }

    public void writeField(
            String declaringClass, String name, String descriptor, long receiverId, long valueId) {
        Field field = findField(findClass(declaringClass), name, descriptor);
        Object receiver = Modifier.isStatic(field.getModifiers()) ? null : objects.resolve(receiverId);
        requireReceiver(field.getDeclaringClass(), receiver, "field", field.getName());
        Object value = objects.resolve(valueId);
        makeAccessible(field);
        try {
            field.set(receiver, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot write field " + declaringClass + "." + name, exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cannot assign " + valueType(value) + " to "
                    + field.getDeclaringClass().getName() + "." + field.getName()
                    + " of type " + field.getType().getName(), exception);
        }
    }

    public boolean isInstance(String className, long objectId) {
        return findClass(className).isInstance(objects.resolve(objectId));
    }

    public String className(long objectId) {
        Object value = objects.resolve(objectId);
        return value == null ? Object.class.getName() : value.getClass().getName();
    }

    public String materialize(long objectId) {
        Object value = objects.resolve(objectId);
        if (value == null) return TextWireCodec.encode("null", "", Object.class.getName());
        if (value instanceof String) return scalar("string", value, value.getClass());
        if (value instanceof Boolean) return scalar("boolean", value, value.getClass());
        if (value instanceof Byte) return scalar("byte", value, value.getClass());
        if (value instanceof Short) return scalar("short", value, value.getClass());
        if (value instanceof Integer) return scalar("int", value, value.getClass());
        if (value instanceof Long) return scalar("long", value, value.getClass());
        if (value instanceof Float) return scalar("float", value, value.getClass());
        if (value instanceof Double) return scalar("double", value, value.getClass());
        if (value instanceof Character) return scalar("char", value, value.getClass());
        if (value instanceof byte[]) {
            return TextWireCodec.encode("bytes", Base64.getEncoder().encodeToString((byte[]) value), "[B");
        }
        if (value instanceof Enum<?>) {
            return TextWireCodec.encode("enum", ((Enum<?>) value).name(), value.getClass().getName());
        }
        return TextWireCodec.encode("display", String.valueOf(value), value.getClass().getName());
    }

    public RemoteObjectDescriptor describe(long objectId) {
        return objects.describe(objectId);
    }

    public void release(long objectId) {
        objects.release(objectId);
    }

    @Override
    public void close() {
        objects.close();
    }

    private static String scalar(String kind, Object value, Class<?> type) {
        return TextWireCodec.encode(kind, String.valueOf(value), type.getName());
    }

    private Object requireArray(long objectId) {
        Object value = objects.resolve(objectId);
        if (value == null || !value.getClass().isArray()) {
            throw new IllegalArgumentException(className(objectId) + " is not an array");
        }
        return value;
    }

    private static void checkIndex(Object array, int index) {
        int length = Array.getLength(array);
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Array index " + index + " is outside [0, " + length + ")");
        }
    }

    private static int checkedLimit(int requestedLimit) {
        if (requestedLimit < 1 || requestedLimit > 10_000) {
            throw new IllegalArgumentException("Limit must be between 1 and 10000");
        }
        return requestedLimit;
    }

    private static boolean isPrimitiveLike(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == Character.class || type == String.class || type.isEnum();
    }

    private static void requireReceiver(Class<?> declaringType, Object receiver, String kind, String name) {
        if (receiver != null && !declaringType.isInstance(receiver)) {
            throw new IllegalArgumentException("Receiver type " + receiver.getClass().getName()
                    + " is not compatible with " + kind + " " + declaringType.getName() + "." + name);
        }
    }

    private static String valueType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String argumentTypes(Object[] arguments) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < arguments.length; index++) {
            if (index != 0) result.append(", ");
            result.append(valueType(arguments[index]));
        }
        return result.append(']').toString();
    }

    @SuppressWarnings("deprecation")
    private static MethodHandles.Lookup trustedLookup(Class<?> lookupClass) throws ReflectiveOperationException {
        Constructor<MethodHandles.Lookup> constructor =
                MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        makeAccessible(constructor);
        return constructor.newInstance(lookupClass, 0x0F);
    }

    private Object[] resolve(long[] ids) {
        Object[] result = new Object[ids.length];
        for (int index = 0; index < ids.length; index++) result[index] = objects.resolve(ids[index]);
        return result;
    }

    private static Constructor<?> findConstructor(Class<?> type, String descriptor) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (JvmDescriptors.of(constructor).equals(descriptor)) return constructor;
        }
        throw new IllegalArgumentException("Constructor was not found: " + type.getName() + descriptor);
    }

    private static Constructor<?> selectConstructor(Class<?> type, Object[] arguments) {
        Constructor<?> selected = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (matches(constructor.getParameterTypes(), arguments)) {
                if (selected != null) {
                    throw new IllegalArgumentException("Constructor selection is ambiguous; provide a descriptor");
                }
                selected = constructor;
            }
        }
        if (selected == null) throw new IllegalArgumentException("No constructor accepts the supplied arguments");
        return selected;
    }

    private static boolean matches(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes.length != arguments.length) return false;
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = arguments[index];
            Class<?> parameter = wrap(parameterTypes[index]);
            if (argument == null ? parameterTypes[index].isPrimitive() : !parameter.isInstance(argument)) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private static Method findMethod(Class<?> type, String name, String descriptor) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && JvmDescriptors.of(method).equals(descriptor)) return method;
            }
        }
        throw new IllegalArgumentException("Method was not found: " + type.getName() + "." + name + descriptor);
    }

    private static Field findField(Class<?> type, String name, String descriptor) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name) && JvmDescriptors.of(field.getType()).equals(descriptor)) return field;
            }
        }
        throw new IllegalArgumentException("Field was not found: " + type.getName() + "." + name + " " + descriptor);
    }

    @SuppressWarnings("deprecation")
    private static void makeAccessible(AccessibleObject object) {
        if (!object.isAccessible()) object.setAccessible(true);
    }

    private static RuntimeException propagate(String operation, Throwable failure) {
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        return new IllegalStateException(operation + ": " + failure, failure);
    }
}
