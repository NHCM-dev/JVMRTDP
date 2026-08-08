package nhcm.jvmrtdp.handles.java;

import java.util.List;
import java.util.Objects;

/** Object-oriented view of a target Iterable. Returned elements are independent handles. */
public class RemoteIterable implements AutoCloseable {
    private final RemoteObject object;

    public RemoteIterable(RemoteObject object) {
        this.object = Objects.requireNonNull(object, "object");
    }

    public RemoteObject object() {
        return object;
    }

    public List<RemoteObject> snapshot(int limit) {
        return object.iterableElements(limit);
    }

    @Override
    public void close() {
        object.close();
    }
}
