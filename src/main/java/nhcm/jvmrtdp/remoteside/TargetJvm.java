package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;

import java.util.Objects;

/**
 * Target-side JNI/JVMTI facade. Native pointers and references never cross the
 * transport; only bounded byte arrays and strings are returned to the controller.
 */
public class TargetJvm {
    public byte[] getClassBytes(String className) {
        return NativeAgent.getClassBytes(requireName(className, "className"));
    }

    public String readStaticFields(String className) {
        return NativeAgent.readStaticFields(requireName(className, "className"));
    }

    public String readStaticField(String className, String fieldName) {
        return NativeAgent.readStaticField(
                requireName(className, "className"), requireName(fieldName, "fieldName"));
    }

    public String callStaticMethod(String className, String methodName, String descriptor, String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return NativeAgent.callStaticMethod(
                requireName(className, "className"),
                requireName(methodName, "methodName"),
                requireName(descriptor, "descriptor"),
                arguments.clone());
    }

    private static String requireName(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
