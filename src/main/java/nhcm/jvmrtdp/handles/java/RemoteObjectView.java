package nhcm.jvmrtdp.handles.java;

import java.util.Objects;

/**
 * A typed view of a remote object. The receiver keeps its runtime identity while member lookup uses
 * the selected class or interface.
 */
public class RemoteObjectView {
    private final RemoteObject object;
    private final RemoteClass type;

    public RemoteObjectView(RemoteObject object, RemoteClass type) {
        this.object = Objects.requireNonNull(object, "object");
        this.type = Objects.requireNonNull(type, "type");
        if (object.server() != type.server()) {
            throw new IllegalArgumentException("Remote object and type belong to different sessions");
        }
        if (!object.isNull() && !type.isInstance(object)) {
            throw new IllegalArgumentException(object.className() + " is not assignable to " + type.className());
        }
    }

    public RemoteObject object() {
        return object;
    }

    public RemoteClass type() {
        return type;
    }

    public RemoteObject readField(String name) {
        return type.getVirtualField(name).read(object);
    }

    public RemoteObject readField(String declaringClass, String name) {
        return type.getVirtualField(declaringClass, name).read(object);
    }

    public void writeField(String name, RemoteObject value) {
        type.getVirtualField(name).write(object, value);
    }

    public void writeField(String declaringClass, String name, RemoteObject value) {
        type.getVirtualField(declaringClass, name).write(object, value);
    }

    /** Uses normal Java virtual dispatch after resolving the method through this view. */
    public RemoteObject call(String name, String descriptor, RemoteObject... arguments) {
        return type.getVirtualMethod(name, descriptor).call(object, arguments);
    }

    /** Calls the exact declaring-class implementation, equivalent to the CLI Parent::method form. */
    public RemoteObject callSpecial(
            String declaringClass, String name, String descriptor, RemoteObject... arguments) {
        return type.getVirtualMethod(declaringClass, name, descriptor).callSpecial(object, arguments);
    }
}
