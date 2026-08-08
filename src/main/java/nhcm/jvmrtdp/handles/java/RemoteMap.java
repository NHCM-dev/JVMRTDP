package nhcm.jvmrtdp.handles.java;

import java.util.List;
import java.util.Objects;

/** Object-oriented view of a target Map. Returned entries own their key/value handles. */
public class RemoteMap implements AutoCloseable {
    private final RemoteObject object;

    public RemoteMap(RemoteObject object) {
        this.object = Objects.requireNonNull(object, "object");
    }

    public RemoteObject object() {
        return object;
    }

    public List<RemoteMapEntry> snapshot(int limit) {
        return object.mapEntries(limit);
    }

    @Override
    public void close() {
        object.close();
    }
}
