package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;

import java.lang.reflect.Array;
import java.lang.ref.WeakReference;
import java.util.Collection;
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

    /** Stores a value without invoking application-defined {@code toString()} code. */
    public RemoteObjectDescriptor storeOpaque(Object value) {
        String type = value == null ? Object.class.getName() : value.getClass().getName();
        long id = ids.getAndIncrement();
        objects.put(id, Entry.strong(value == null ? NULL : value, type));
        String display;
        if (value == null) display = "null";
        else if (value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            display = String.valueOf(value);
        } else if (value instanceof Enum<?>) {
            display = ((Enum<?>) value).name();
        } else {
            display = type + "@" + Integer.toHexString(System.identityHashCode(value)) + sizeSuffix(value);
        }
        return new RemoteObjectDescriptor(id, value == null, type, display);
    }

    public RemoteObjectDescriptor store(Object value, String declaredType) {
        long id = ids.getAndIncrement();
        objects.put(id, Entry.strong(value == null ? NULL : value, declaredType));
        return descriptor(id, value, declaredType);
    }

    /** Creates an independently releasable strong or weak handle for an existing value. */
    public RemoteObjectDescriptor retain(long sourceId, boolean weak) {
        Entry source = requireEntry(sourceId);
        Object stored = source.value(sourceId);
        Object value = stored == NULL ? null : stored;
        long id = ids.getAndIncrement();
        objects.put(id, weak && value != null
                ? Entry.weak(value, source.declaredType)
                : Entry.strong(value == null ? NULL : value, source.declaredType));
        return descriptor(id, value, source.declaredType);
    }

    /** Distinguishes a collected weak handle from an explicit Java null. */
    public String status(long id) {
        Entry entry = objects.get(id);
        if (entry == null) return "released";
        Object value = entry.rawValue();
        if (value == null && entry.weak) return "collected";
        return value == NULL ? "null" : "live";
    }

    public Object resolve(long id) {
        Object value = requireEntry(id).value(id);
        return value == NULL ? null : value;
    }

    public RemoteObjectDescriptor describe(long id) {
        Entry entry = requireEntry(id);
        Object stored = entry.value(id);
        Object value = stored == NULL ? null : stored;
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

    private Entry requireEntry(long id) {
        Entry entry = objects.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown or released remote object: " + id);
        return entry;
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
        String sizeSuffix = sizeSuffix(value);
        int displayLimit = MAX_DISPLAY_LENGTH - sizeSuffix.length();
        if (display.length() > displayLimit) {
            display = display.substring(0, displayLimit - 3) + "...";
        }
        display += sizeSuffix;
        String type = value == null ? declaredType : value.getClass().getName();
        return new RemoteObjectDescriptor(id, value == null, type, display);
    }

    private static String sizeSuffix(Object value) {
        if (value == null) return "";
        try {
            Class<?> type = value.getClass();
            if (type.isArray()) return " [size=" + Array.getLength(value) + "]";
            if (value instanceof Collection<?>) {
                return " [size=" + ((Collection<?>) value).size() + "]";
            }
            if (value instanceof Map<?, ?>) return " [size=" + ((Map<?, ?>) value).size() + "]";
            // Iterable itself intentionally has no size contract. Do not consume an iterator merely
            // to render a diagnostic summary: it may be infinite, stateful or expensive.
            if (value instanceof Iterable<?>) return " [size=unknown]";
            return "";
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return " [size=unavailable]";
        }
    }

    private static class Entry {
        private final Object strongValue;
        private final WeakReference<Object> weakValue;
        private final boolean weak;
        private final String declaredType;

        private Entry(Object strongValue, WeakReference<Object> weakValue,
                boolean weak, String declaredType) {
            this.strongValue = strongValue;
            this.weakValue = weakValue;
            this.weak = weak;
            this.declaredType = declaredType;
        }

        private static Entry strong(Object value, String declaredType) {
            return new Entry(value, null, false, declaredType);
        }

        private static Entry weak(Object value, String declaredType) {
            return new Entry(null, new WeakReference<Object>(value), true, declaredType);
        }

        private Object rawValue() { return weak ? weakValue.get() : strongValue; }

        private Object value(long id) {
            Object value = rawValue();
            if (value == null && weak) {
                throw new IllegalStateException("Weak remote object has been garbage collected: " + id);
            }
            return value;
        }
    }
}
