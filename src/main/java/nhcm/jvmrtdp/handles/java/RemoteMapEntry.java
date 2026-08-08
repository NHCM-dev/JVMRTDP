package nhcm.jvmrtdp.handles.java;

import java.util.Objects;

/** A target Map entry represented by two independently releasable handles. */
public class RemoteMapEntry implements AutoCloseable {
    private final RemoteObject key;
    private final RemoteObject value;

    public RemoteMapEntry(RemoteObject key, RemoteObject value) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
    }

    public RemoteObject key() {
        return key;
    }

    public RemoteObject value() {
        return value;
    }

    @Override
    public void close() {
        try {
            key.close();
        } finally {
            value.close();
        }
    }
}
