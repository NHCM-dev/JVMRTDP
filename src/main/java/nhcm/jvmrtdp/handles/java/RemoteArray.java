package nhcm.jvmrtdp.handles.java;

import java.util.Objects;

/** Object-oriented view of a target primitive or reference array. */
public class RemoteArray implements AutoCloseable {
    private final RemoteObject object;

    public RemoteArray(RemoteObject object) {
        this.object = Objects.requireNonNull(object, "object");
    }

    public RemoteObject object() {
        return object;
    }

    public int length() {
        return object.arrayLength();
    }

    public RemoteObject get(int index) {
        return object.arrayGet(index);
    }

    public void set(int index, RemoteObject value) {
        object.arraySet(index, value);
    }

    @Override
    public void close() {
        object.close();
    }
}
