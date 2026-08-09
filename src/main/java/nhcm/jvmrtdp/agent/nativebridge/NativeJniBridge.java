package nhcm.jvmrtdp.agent.nativebridge;

/** JNI operations that work with classes, fields, methods and class loaders. */
public final class NativeJniBridge {
    public static final String BINDING_CLASS =
            "nhcm/jvmrtdp/agent/nativebridge/NativeJniBridge";

    private NativeJniBridge() {
    }

    public static native String readStaticFields(String className);

    public static native String readStaticField(String className, String fieldName);

    public static native String callStaticMethod(
            String className, String methodName, String descriptor, String[] arguments);

    public static native Class<?> findLoadedClass(String className);

    public static native String[] listLoadedClassNames();

    public static native Class<?>[] listLoadedClasses();

    public static native Class<?> defineClass(String className, byte[] classBytes, ClassLoader loader);
}
