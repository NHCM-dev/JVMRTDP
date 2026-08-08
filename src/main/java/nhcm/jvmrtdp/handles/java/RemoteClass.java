package nhcm.jvmrtdp.handles.java;

import nhcm.jvmrtdp.handles.RemoteHandle;
import nhcm.jvmrtdp.handles.ServerHandle;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class RemoteClass extends RemoteHandle {
    private final String className;
    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;

    public RemoteClass(
            ServerHandle server,
            long remoteId,
            String className,
            RemoteJNIEnv jni,
            RemoteJVMTIEnv jvmti) {
        super(server, remoteId);
        this.className = Objects.requireNonNull(className, "className");
        if (className.trim().isEmpty()) throw new IllegalArgumentException("className must not be empty");
        this.jni = Objects.requireNonNull(jni, "jni");
        this.jvmti = Objects.requireNonNull(jvmti, "jvmti");
    }

    public String className() {
        return className;
    }

    public byte[] getClassBytes() {
        return jvmti.getClassBytes(className);
    }

    public Path dumpClass(Path outputFile) throws IOException {
        return jvmti.dumpClass(className, outputFile);
    }

    public RemoteClassInfo info() {
        return jni.classInfo(this);
    }

    public List<RemoteConstructor> getConstructors() {
        return jni.listConstructors(this);
    }

    public List<RemoteMethod> getStaticMethods() {
        return jni.listMethods(this, true);
    }

    public List<RemoteMethod> getVirtualMethods() {
        return jni.listMethods(this, false);
    }

    public List<RemoteField> getStaticFields() {
        return jni.listFields(this, true);
    }

    public List<RemoteField> getVirtualFields() {
        return jni.listFields(this, false);
    }

    public RemoteConstructor getConstructor(String descriptor) {
        for (RemoteConstructor constructor : getConstructors()) {
            if (constructor.descriptor().equals(descriptor)) return constructor;
        }
        throw new IllegalArgumentException("Constructor was not found: " + className + descriptor);
    }

    public RemoteMethod getStaticMethod(String name, String descriptor) {
        return method(getStaticMethods(), name, descriptor);
    }

    public RemoteMethod getStaticMethod(String declaringClass, String name, String descriptor) {
        return method(getStaticMethods(), declaringClass, name, descriptor);
    }

    public RemoteMethod getVirtualMethod(String name, String descriptor) {
        return method(getVirtualMethods(), name, descriptor);
    }

    public RemoteMethod getVirtualMethod(String declaringClass, String name, String descriptor) {
        return method(getVirtualMethods(), declaringClass, name, descriptor);
    }

    public RemoteField getStaticField(String name) {
        return field(getStaticFields(), name);
    }

    public RemoteField getStaticField(String declaringClass, String name) {
        return field(getStaticFields(), declaringClass, name);
    }

    public RemoteField getVirtualField(String name) {
        return field(getVirtualFields(), name);
    }

    public RemoteField getVirtualField(String declaringClass, String name) {
        return field(getVirtualFields(), declaringClass, name);
    }

    public boolean isInstance(RemoteObject object) {
        return jni.isInstance(this, object);
    }

    public RemoteObjectView view(RemoteObject object) {
        return new RemoteObjectView(object, this);
    }

    public RemoteObject construct(String descriptor, RemoteObject... arguments) {
        return jni.construct(this, descriptor, arguments);
    }

    public RemoteObject construct(RemoteObject... arguments) {
        return construct("auto", arguments);
    }

    RemoteJNIEnv jniEnv() {
        return jni;
    }

    private static RemoteMethod method(List<RemoteMethod> methods, String name, String descriptor) {
        return method(methods, null, name, descriptor);
    }

    private static RemoteMethod method(
            List<RemoteMethod> methods, String declaringClass, String name, String descriptor) {
        for (RemoteMethod method : methods) {
            if ((declaringClass == null || method.declaringClass().equals(declaringClass))
                    && method.name().equals(name) && method.descriptor().equals(descriptor)) return method;
        }
        throw new IllegalArgumentException("Method was not found: "
                + (declaringClass == null ? "" : declaringClass + "::") + name + descriptor);
    }

    private static RemoteField field(List<RemoteField> fields, String name) {
        return field(fields, null, name);
    }

    private static RemoteField field(List<RemoteField> fields, String declaringClass, String name) {
        for (RemoteField field : fields) {
            if ((declaringClass == null || field.declaringClass().equals(declaringClass))
                    && field.name().equals(name)) return field;
        }
        throw new IllegalArgumentException("Field was not found: "
                + (declaringClass == null ? "" : declaringClass + "::") + name);
    }
}
