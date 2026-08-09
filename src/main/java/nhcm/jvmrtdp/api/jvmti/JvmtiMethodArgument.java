package nhcm.jvmrtdp.api.jvmti;

/** One method argument captured from its local-variable slot. */
public class JvmtiMethodArgument {
    private final int index;
    private final int slot;
    private final String name;
    private final String descriptor;
    private final Object value;
    private final String error;

    public JvmtiMethodArgument(int index, int slot, String name, String descriptor,
            Object value, String error) {
        this.index = index;
        this.slot = slot;
        this.name = emptyToNull(name);
        this.descriptor = descriptor;
        this.value = value;
        this.error = emptyToNull(error);
    }

    public int index() { return index; }
    public int slot() { return slot; }
    /** Source parameter name when LocalVariableTable debug information is present. */
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    /** Boxed primitive, object/array reference, or null. Check {@link #available()} first. */
    public Object value() { return value; }
    public boolean available() { return error == null; }
    /** JVMTI error text when this slot could not be read. */
    public String error() { return error; }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public String toString() {
        String label = name == null ? "arg" + index : name;
        return label + "@" + slot + ":" + descriptor + "="
                + (available() ? String.valueOf(value) : "<unavailable: " + error + ">");
    }
}
