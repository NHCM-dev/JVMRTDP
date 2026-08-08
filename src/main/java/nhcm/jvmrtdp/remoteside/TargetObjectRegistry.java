package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Strong references scoped to one authenticated session. */
public class TargetObjectRegistry implements AutoCloseable {
    private static final Object NULL = new Object();
    private static final int MAX_DISPLAY_LENGTH = 4_096;

    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, Entry> objects = new ConcurrentHashMap<Long, Entry>();

    public RemoteObjectDescriptor store(Object value) {
        return store(value, value == null ? Object.class.getName() : value.getClass().getName());
    }

    public RemoteObjectDescriptor store(Object value, String declaredType) {
        long id = ids.getAndIncrement();
        objects.put(id, new Entry(value == null ? NULL : value, declaredType));
        return descriptor(id, value, declaredType);
    }

    public Object resolve(long id) {
        Entry entry = objects.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown or released remote object: " + id);
        }
        return entry.value == NULL ? null : entry.value;
    }

    public RemoteObjectDescriptor describe(long id) {
        Entry entry = objects.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown or released remote object: " + id);
        }
        Object value = entry.value == NULL ? null : entry.value;
        return descriptor(id, value, entry.declaredType);
    }

    public void release(long id) {
        objects.remove(id);
    }

    public int size() {
        return objects.size();
    }

    @Override
    public void close() {
        objects.clear();
    }

    private static RemoteObjectDescriptor descriptor(long id, Object value, String declaredType) {
        String display;
        if (value == null) {
            display = "null";
        } else {
            try {
                display = String.valueOf(value);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                display = "<toString failed: " + failure + ">";
            }
        }
        if (display.length() > MAX_DISPLAY_LENGTH) {
            display = display.substring(0, MAX_DISPLAY_LENGTH - 3) + "...";
        }
        String type = value == null ? declaredType : value.getClass().getName();
        return new RemoteObjectDescriptor(id, value == null, type, display);
    }

    private static class Entry {
        private final Object value;
        private final String declaredType;

        private Entry(Object value, String declaredType) {
            this.value = value;
            this.declaredType = declaredType;
        }
    }
}
