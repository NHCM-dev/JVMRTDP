package nhcm.jvmrtdp.controllerside.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClassFileView {
    private final String className;
    private final int minorVersion;
    private final int majorVersion;
    private final List<ClassFileMethod> methods;
    private final List<String> constants;

    ClassFileView(String className, int minorVersion, int majorVersion,
            List<ClassFileMethod> methods, List<String> constants) {
        this.className = className;
        this.minorVersion = minorVersion;
        this.majorVersion = majorVersion;
        this.methods = Collections.unmodifiableList(new ArrayList<ClassFileMethod>(methods));
        this.constants = Collections.unmodifiableList(new ArrayList<String>(constants));
    }

    public String className() { return className; }
    public int minorVersion() { return minorVersion; }
    public int majorVersion() { return majorVersion; }
    public List<ClassFileMethod> methods() { return methods; }
    public List<String> constants() { return constants; }

    public ClassFileMethod method(String name, String descriptor) {
        for (ClassFileMethod method : methods) {
            if (method.name().equals(name) && method.descriptor().equals(descriptor)) return method;
        }
        throw new IllegalArgumentException("Method was not found in class bytes: " + name + descriptor);
    }
}
